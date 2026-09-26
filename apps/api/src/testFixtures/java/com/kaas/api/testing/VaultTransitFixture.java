package com.kaas.api.testing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Vault, provisioned the way the Operations contract describes production, for tests.
 *
 * <h2>What is real here and what is not</h2>
 *
 * <p>Real: the Vault binary and version (1.18.5, pinned by the multi-architecture index digest), TLS with a CA
 * that is not in any trust store, the Transit engine, a {@code kaas-tenant-secrets} key of type
 * {@code aes256-gcm96} with {@code derived=true}, not exportable, deletion not allowed, and an AppRole whose
 * policy grants exactly {@code update} on encrypt and decrypt for that one key and nothing else. The control
 * plane authenticates through that AppRole with a runtime-generated role id and secret id, exactly as it does in
 * production.
 *
 * <p>Not real, and stated rather than hidden: Vault runs in dev mode with in-memory storage, because that is
 * what makes a disposable, unsealed Vault possible in a CI job. Dev mode is used ONLY to bootstrap it. Its root
 * token is random per run, is used by this fixture alone to provision the engine, the key, the policy and the
 * role, and is never given to the application under test. A test that let the control plane use the root token
 * would be testing a permission model production does not have.
 *
 * <p>Nothing printed by this class ever contains the root token, the role id, the secret id or a plaintext.
 */
public final class VaultTransitFixture implements AutoCloseable {

    /** Vault 1.18.5, the Operations-selected version, pinned by its multi-architecture index digest. */
    public static final String IMAGE =
            "hashicorp/vault:1.18.5@sha256:750bb37c1638fa194ab37053a81618c61bb0491ddec6fccac87c07a8e6cd8166";

    public static final String TRANSIT_KEY = "kaas-tenant-secrets";

    private static final String ROLE = "kaas-control-plane";

    private static final String POLICY = "kaas-transit";

    private final GenericContainer<?> container;
    private final String rootToken = UUID.randomUUID().toString();
    private final Path caFile;
    private final HttpClient http;
    private final String roleId;
    private final String secretId;

    @SuppressWarnings("resource")
    public VaultTransitFixture() {
        this.container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", rootToken)
                .withEnv("SKIP_SETCAP", "true")
                .withCommand(
                        "server", "-dev", "-dev-tls", "-dev-tls-cert-dir=/tmp", "-dev-listen-address=0.0.0.0:8200")
                .withExposedPorts(8200)
                .withLabel("kaas.test.resource", "vault");
        try {
            container.start();
            this.caFile = Files.createTempFile("kaas-vault-ca-", ".pem");
            waitForCertificate();
            this.http = HttpClient.newBuilder()
                    .sslContext(trusting(caFile))
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            waitForHealth();
            provision();
            this.roleId = extract(root("GET", "/v1/auth/approle/role/" + ROLE + "/role-id", null), "role_id");
            this.secretId = extract(root("POST", "/v1/auth/approle/role/" + ROLE + "/secret-id", "{}"), "secret_id");
        } catch (RuntimeException | IOException | InterruptedException failure) {
            container.stop();
            throw new IllegalStateException("The test Vault could not be provisioned.", failure);
        }
    }

    /** {@code https://localhost:<port>} — the name the dev certificate carries, so hostname checks stay on. */
    public String address() {
        return "https://localhost:" + container.getMappedPort(8200);
    }

    public Path caFile() {
        return caFile;
    }

    public String roleId() {
        return roleId;
    }

    public String secretId() {
        return secretId;
    }

    /** Whether the container is still running, for tests that stopped it. */
    public boolean running() {
        return container.isRunning();
    }

    /**
     * Decrypts a stored ciphertext with the ROOT token, as independent evidence that what the platform stored is
     * a real Transit ciphertext for the given context. Never used by the platform under test.
     */
    public byte[] decryptAsOperator(String ciphertext, UUID organizationId, UUID projectId) {
        String context = Base64.getEncoder().encodeToString(
                ("org:" + organizationId + "/project:" + projectId).getBytes(StandardCharsets.US_ASCII));
        try {
            String response = root("POST", "/v1/transit/decrypt/" + TRANSIT_KEY,
                    "{\"ciphertext\":\"" + ciphertext + "\",\"context\":\"" + context + "\"}");
            return Base64.getDecoder().decode(extract(response, "plaintext"));
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException("The operator decryption did not complete.");
        }
    }

    /** Whether the operator can decrypt it under this context at all; false for a wrong-tenant context. */
    public boolean operatorCanDecrypt(String ciphertext, UUID organizationId, UUID projectId) {
        try {
            decryptAsOperator(ciphertext, organizationId, projectId);
            return true;
        } catch (IllegalStateException refused) {
            return false;
        }
    }

    /** Seals Vault. Every Transit call then fails with 503, which is how a sealed production Vault behaves. */
    public void seal() {
        try {
            root("PUT", "/v1/sys/seal", null);
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException("Vault could not be sealed.");
        }
    }

    /** Revokes every token the AppRole has issued, the way an operator revoking a leaked token would. */
    public void revokeIssuedTokens() {
        try {
            root("PUT", "/v1/sys/leases/revoke-prefix/auth/approle/login", null);
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException("The issued tokens could not be revoked.");
        }
    }

    /** Destroys every secret id for the role, so a fresh login can no longer succeed. */
    public void destroySecretIds() {
        try {
            root("POST", "/v1/auth/approle/role/" + ROLE + "/secret-id/destroy",
                    "{\"secret_id\":\"" + secretId + "\"}");
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException("The secret id could not be destroyed.");
        }
    }

    /** Rotates the Transit key, which is a KMS operation and deliberately not a tenant secret rotation. */
    public void rotateTransitKey() {
        try {
            root("POST", "/v1/transit/keys/" + TRANSIT_KEY + "/rotate", "{}");
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException("The Transit key could not be rotated.");
        }
    }

    /** Stops Vault entirely: an unreachable provider rather than a refusing one. */
    public void stop() {
        container.stop();
    }

    @Override
    public void close() {
        container.stop();
        try {
            Files.deleteIfExists(caFile);
        } catch (IOException ignored) {
            // A temp file of a public CA certificate; nothing sensitive is left behind.
        }
    }

    private void provision() throws IOException, InterruptedException {
        root("POST", "/v1/sys/mounts/transit", "{\"type\":\"transit\"}");
        root("POST", "/v1/transit/keys/" + TRANSIT_KEY,
                "{\"type\":\"aes256-gcm96\",\"derived\":true,\"exportable\":false,\"allow_plaintext_backup\":false}");
        // deletion_allowed defaults to false; stated so the key's configuration here reads like the contract.
        root("POST", "/v1/transit/keys/" + TRANSIT_KEY + "/config", "{\"deletion_allowed\":false}");
        root("POST", "/v1/sys/auth/approle", "{\"type\":\"approle\"}");
        String policy = "path \\\"transit/encrypt/" + TRANSIT_KEY + "\\\" { capabilities = [\\\"update\\\"] }\\n"
                + "path \\\"transit/decrypt/" + TRANSIT_KEY + "\\\" { capabilities = [\\\"update\\\"] }\\n";
        root("PUT", "/v1/sys/policies/acl/" + POLICY, "{\"policy\":\"" + policy + "\"}");
        root("POST", "/v1/auth/approle/role/" + ROLE,
                "{\"token_policies\":[\"" + POLICY + "\"],\"token_no_default_policy\":true,"
                        + "\"token_ttl\":\"20m\",\"token_max_ttl\":\"1h\",\"secret_id_num_uses\":0}");
    }

    private String root(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(address() + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Vault-Token", rootToken)
                .header("X-Vault-Request", "true");
        request = switch (method) {
            case "GET" -> request.GET();
            case "PUT" -> request.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            default -> request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        };
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            // Status only. A Vault error body can echo the request, and this request may carry a secret id.
            throw new IllegalStateException(method + " " + path + " answered " + response.statusCode());
        }
        return response.body();
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("Vault's answer did not carry " + field + ".");
        }
        return matcher.group(1);
    }

    private void waitForCertificate() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try {
                container.copyFileFromContainer("/tmp/vault-ca.pem", caFile.toString());
                if (Files.size(caFile) > 0) {
                    return;
                }
            } catch (RuntimeException | IOException notYet) {
                // Vault writes its dev CA a moment after the process starts.
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Vault never wrote its dev CA.");
            }
            Thread.sleep(200);
        }
    }

    private void waitForHealth() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try {
                HttpResponse<Void> health = http.send(
                        HttpRequest.newBuilder(URI.create(address() + "/v1/sys/health")).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (health.statusCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                // Listener not up yet.
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Vault never became healthy.");
            }
            Thread.sleep(200);
        }
    }

    private static SSLContext trusting(Path ca) {
        try (InputStream pem = Files.newInputStream(ca)) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(pem)) {
                store.setCertificateEntry("ca-" + index++, certificate);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (Exception failure) {
            throw new IllegalStateException("The test Vault's CA could not be loaded.", failure);
        }
    }
}
