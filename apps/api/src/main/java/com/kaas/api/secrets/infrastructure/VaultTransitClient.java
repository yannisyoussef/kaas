package com.kaas.api.secrets.infrastructure;

import com.kaas.api.secrets.domain.SecretFailure;
import com.kaas.api.secrets.domain.SecretProviderException;
import com.kaas.api.secrets.domain.SecretTransit;
import com.kaas.api.secrets.domain.SecretValuePolicy;
import com.kaas.api.secrets.domain.TransitCiphertext;
import com.kaas.api.secrets.domain.TransitContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vault Transit, spoken directly over HTTPS with the JDK's own client.
 *
 * <h2>Why no SDK</h2>
 *
 * <p>The repository forbids provider SDKs on the control plane's shipped classpath, and this slice keeps that
 * rule rather than amending it. Three endpoints are used — AppRole login, Transit encrypt, Transit decrypt —
 * and each is one small JSON request. A client library would bring a configuration surface (token sources, auth
 * backends, retry policies, TLS toggles) that is larger than the protocol being used, and every option in it is
 * one that has to be proved off. This file is the whole of the surface, and it has no switch that disables TLS
 * verification because it has no switches.
 *
 * <h2>What it never does</h2>
 *
 * <ul>
 *   <li>It never logs. Not the token, not a request, not a response, not a status line.</li>
 *   <li>It never attaches a provider's response body to anything. Failures are categories
 *       ({@link SecretFailure}) and carry no cause.</li>
 *   <li>It never turns plaintext into a {@code String}. Requests are assembled as bytes and decrypted values are
 *       decoded straight out of the parser's character buffer, so the only immutable copies are the ones the
 *       JDK's own transport makes, which no code here can reach or clear. That is stated as a limit, not
 *       claimed as a guarantee: a managed runtime copies buffers and none of this is zeroisation.</li>
 *   <li>It never caches a value. The Vault token is cached; nothing it decrypts is.</li>
 * </ul>
 *
 * <h2>Token lifetime</h2>
 *
 * <p>AppRole login yields a token with a TTL (the Ops contract is about twenty minutes, at most an hour). The
 * token is held in memory only, refreshed by a fresh login once three quarters of its lease has elapsed, and
 * re-obtained once if Vault answers 403 — a token revoked early by an operator is a reason to log in again, not
 * a reason to fail every operation until the cache would have expired on its own.
 */
public final class VaultTransitClient implements SecretTransit {

    /** Upper bound on any response this client will read. The largest legitimate one is far below it. */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /** A token is refreshed well before Vault would expire it, and never kept longer than this. */
    private static final Duration MAX_TOKEN_REUSE = Duration.ofMinutes(20);

    private static final Duration MIN_TOKEN_REUSE = Duration.ofSeconds(5);

    private final VaultTransitSettings settings;
    private final HttpClient http;
    private final LongSupplier nanoTime;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private String token;
    private long refreshAtNanos;

    public VaultTransitClient(VaultTransitSettings settings) {
        this(settings, HttpClient.newBuilder()
                .sslContext(trusting(settings.caCertificate()))
                .connectTimeout(settings.connectTimeout())
                // A redirect would send the token to wherever the response pointed. Vault never needs one.
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build(), System::nanoTime);
    }

    VaultTransitClient(VaultTransitSettings settings, HttpClient http, LongSupplier nanoTime) {
        this.settings = settings;
        this.http = http;
        this.nanoTime = nanoTime;
    }

    /**
     * An SSL context that trusts exactly the configured CA and nothing else.
     *
     * <p>Not the JDK's default trust store plus this CA: the key service is internal, and a certificate for its
     * name issued by any public CA is not a certificate the platform should accept. Hostname verification is
     * the JDK client's default and is not touched.
     */
    static SSLContext trusting(Path caCertificate) {
        try (InputStream pem = Files.newInputStream(caCertificate)) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(pem);
            if (certificates.isEmpty()) {
                throw new IllegalStateException("KAAS_VAULT_CACERT contains no certificate.");
            }
            KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
            trusted.load(null, null);
            int index = 0;
            for (Certificate certificate : certificates) {
                trusted.setCertificateEntry("kaas-vault-ca-" + index++, certificate);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trusted);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException unreadable) {
            // The category, not the exception: a parse failure on a PEM can quote the file's bytes.
            throw new IllegalStateException("KAAS_VAULT_CACERT could not be read as an X.509 certificate.");
        }
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public EncryptedValue encrypt(TransitContext context, byte[] plaintext) throws SecretProviderException {
        SecretValuePolicy.requireValid(plaintext);
        byte[] encoded = Base64.getEncoder().encode(plaintext);
        byte[] body = null;
        try {
            body = json(new byte[][] {
                bytes("{\"plaintext\":\""), encoded, bytes("\",\"context\":\""),
                bytes(context.base64()), bytes("\"}")
            });
            byte[] response = transit("encrypt", body, SecretFailure.SECRET_VALUE_INVALID);
            JsonNode data = readData(response);
            JsonNode ciphertext = data.get("ciphertext");
            if (ciphertext == null || !ciphertext.isString()
                    || !TransitCiphertext.isWellFormed(ciphertext.stringValue())) {
                throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
            }
            String value = ciphertext.stringValue();
            return new EncryptedValue(value, settings.transitKey(), TransitCiphertext.keyVersionOf(value));
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (body != null) {
                Arrays.fill(body, (byte) 0);
            }
        }
    }

    @Override
    public byte[] decrypt(TransitContext context, String ciphertext) throws SecretProviderException {
        if (!TransitCiphertext.isWellFormed(ciphertext)) {
            // A stored row that is not a Transit ciphertext was not written by this code path.
            throw new SecretProviderException(SecretFailure.SECRET_VALUE_INVALID);
        }
        byte[] body = json(new byte[][] {
            bytes("{\"ciphertext\":\""), bytes(ciphertext), bytes("\",\"context\":\""),
            bytes(context.base64()), bytes("\"}")
        });
        // A 400 from decrypt means the authentication tag did not verify under this context -- a ciphertext
        // presented as belonging to a tenant it does not belong to, or one that was altered at rest.
        byte[] response = transit("decrypt", body, SecretFailure.SECRET_ACCESS_DENIED);
        try {
            byte[] plaintext = plaintextOf(response);
            try {
                SecretValuePolicy.requireValid(plaintext);
            } catch (SecretProviderException invalid) {
                Arrays.fill(plaintext, (byte) 0);
                throw invalid;
            }
            return plaintext;
        } finally {
            Arrays.fill(response, (byte) 0);
        }
    }

    /** One Transit operation, with a single re-login if the cached token was refused. */
    private byte[] transit(String operation, byte[] body, SecretFailure onBadRequest) throws SecretProviderException {
        try {
            URI endpoint = settings.address().resolve("/v1/transit/" + operation + "/" + settings.transitKey());
            Exchange first = post(endpoint, body, currentToken());
            if (first.status() == 403) {
                // Revoked early, or expired sooner than its lease said. One fresh login, then an honest answer.
                invalidate();
                first = post(endpoint, body, currentToken());
                if (first.status() == 403) {
                    throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_AUTH_FAILED);
                }
            }
            return switch (first.status()) {
                case 200 -> first.body();
                case 400 -> {
                    first.clear();
                    throw new SecretProviderException(onBadRequest);
                }
                default -> {
                    // 404 (no such key or engine), 429, 500, 503 (sealed or standby). The provider cannot serve
                    // this now; which of those it was is an operator's question and is visible in Vault's own
                    // audit log, where it belongs.
                    first.clear();
                    throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
                }
            };
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private synchronized void invalidate() {
        token = null;
    }

    /** The cached token, or a fresh one once three quarters of the old one's lease has gone. */
    private synchronized String currentToken() throws SecretProviderException {
        if (token != null && nanoTime.getAsLong() - refreshAtNanos < 0) {
            return token;
        }
        byte[] credentials = json(new byte[][] {
            bytes("{\"role_id\":"), bytes(quoted(settings.roleId())), bytes(",\"secret_id\":"),
            bytes(quoted(settings.secretId())), bytes("}")
        });
        Exchange login;
        try {
            login = post(settings.address().resolve("/v1/auth/approle/login"), credentials, null);
        } finally {
            Arrays.fill(credentials, (byte) 0);
        }
        if (login.status() == 400 || login.status() == 403) {
            login.clear();
            throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_AUTH_FAILED);
        }
        if (login.status() != 200) {
            login.clear();
            throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        }
        try {
            JsonNode auth = mapper.readTree(login.body()).get("auth");
            JsonNode clientToken = auth == null ? null : auth.get("client_token");
            JsonNode lease = auth == null ? null : auth.get("lease_duration");
            if (clientToken == null || !clientToken.isString() || clientToken.stringValue().isBlank()) {
                throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_AUTH_FAILED);
            }
            Duration leaseDuration = lease != null && lease.canConvertToLong() && lease.asLong() > 0
                    ? Duration.ofSeconds(lease.asLong())
                    : MAX_TOKEN_REUSE;
            Duration reuse = leaseDuration.multipliedBy(3).dividedBy(4);
            if (reuse.compareTo(MAX_TOKEN_REUSE) > 0) {
                reuse = MAX_TOKEN_REUSE;
            }
            if (reuse.compareTo(MIN_TOKEN_REUSE) < 0) {
                reuse = MIN_TOKEN_REUSE;
            }
            String fresh = clientToken.stringValue();
            requireTenantSeparatingKey(fresh);
            token = fresh;
            refreshAtNanos = nanoTime.getAsLong() + reuse.toNanos();
            return token;
        } catch (RuntimeException unreadable) {
            throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_AUTH_FAILED);
        } finally {
            login.clear();
        }
    }

    /**
     * Refuses a key that would not separate tenants, read with each fresh token before it is used or cached.
     *
     * <p>The per-project context is only a boundary if the key is {@code derived}: Vault ignores the context
     * of a key that is not, so every tenant's ciphertext would decrypt under every other tenant's context and
     * nothing would say so -- the SQL scoping would be the only line left. A key that is convergent, exportable
     * or deletable is refused for the same reason: each is a property ADR-034 relies on being absent. Checked at
     * login rather than at startup so that a platform with Vault down still starts and still runs secret-free
     * work, and checked on every login so that a key reconfigured under a running control plane is noticed
     * within one token lifetime. The refusal is {@code SECRET_PROVIDER_UNAVAILABLE}: a misconfigured provider
     * is one that cannot serve this operation, and which property failed is an operator's question.
     */
    private void requireTenantSeparatingKey(String freshToken) throws SecretProviderException {
        Exchange read = send(settings.address().resolve("/v1/transit/keys/" + settings.transitKey()), null, freshToken);
        try {
            if (read.status() != 200) {
                throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
            }
            JsonNode data = mapper.readTree(read.body()).get("data");
            boolean separating = data != null
                    && data.path("derived").isBoolean() && data.path("derived").asBoolean()
                    && !data.path("convergent_encryption").asBoolean(false)
                    && !data.path("exportable").asBoolean(true)
                    && !data.path("deletion_allowed").asBoolean(true);
            if (!separating) {
                throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
            }
        } catch (RuntimeException unreadable) {
            throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        } finally {
            read.clear();
        }
    }

    private Exchange post(URI endpoint, byte[] body, String vaultToken) throws SecretProviderException {
        return send(endpoint, body, vaultToken);
    }

    /** A POST of {@code body}, or a GET when there is none. */
    private Exchange send(URI endpoint, byte[] body, String vaultToken) throws SecretProviderException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(settings.requestTimeout())
                .header("X-Vault-Request", "true");
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        if (vaultToken != null) {
            request.header("X-Vault-Token", vaultToken);
        }
        try {
            HttpResponse<InputStream> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] read = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (read.length > MAX_RESPONSE_BYTES) {
                    Arrays.fill(read, (byte) 0);
                    throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
                }
                return new Exchange(response.statusCode(), read);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        } catch (IOException | RuntimeException unreachable) {
            // Timeouts, refused connections, TLS failures (an untrusted certificate or a hostname mismatch
            // lands here too, and that is the intent: an unverified peer is an unavailable provider).
            throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        }
    }

    private JsonNode readData(byte[] response) throws SecretProviderException {
        try {
            JsonNode data = mapper.readTree(response).get("data");
            if (data == null || !data.isObject()) {
                throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
            }
            return data;
        } catch (RuntimeException unreadable) {
            throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        } finally {
            Arrays.fill(response, (byte) 0);
        }
    }

    /**
     * Decodes {@code data.plaintext} without ever materialising it as a {@code String}.
     *
     * <p>The streaming parser exposes its own character buffer; the base64 characters are copied into a byte
     * array, decoded, and the copy cleared. The parser's internal buffer is the parser's — it is released with
     * it and is not something this code can clear.
     */
    private byte[] plaintextOf(byte[] response) throws SecretProviderException {
        try (JsonParser parser = mapper.createParser(response)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
            }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (!"data".equals(field) || value != JsonToken.START_OBJECT) {
                    parser.skipChildren();
                    continue;
                }
                byte[] plaintext = null;
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String inner = parser.currentName();
                    JsonToken innerValue = parser.nextToken();
                    if ("plaintext".equals(inner) && innerValue == JsonToken.VALUE_STRING && plaintext == null) {
                        plaintext = decodeBase64(
                                parser.getStringCharacters(), parser.getStringOffset(), parser.getStringLength());
                    } else {
                        parser.skipChildren();
                    }
                }
                if (plaintext == null) {
                    throw new SecretProviderException(SecretFailure.SECRET_VALUE_INVALID);
                }
                return plaintext;
            }
            throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        } catch (RuntimeException unreadable) {
            throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        }
    }

    private static byte[] decodeBase64(char[] characters, int offset, int length) throws SecretProviderException {
        byte[] ascii = new byte[length];
        try {
            for (int index = 0; index < length; index++) {
                char character = characters[offset + index];
                if (character > 0x7f) {
                    throw new SecretProviderException(SecretFailure.SECRET_VALUE_INVALID);
                }
                ascii[index] = (byte) character;
            }
            return Base64.getDecoder().decode(ascii);
        } catch (IllegalArgumentException malformed) {
            throw new SecretProviderException(SecretFailure.SECRET_VALUE_INVALID);
        } finally {
            Arrays.fill(ascii, (byte) 0);
        }
    }

    private static byte[] json(byte[][] parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        byte[] assembled = out.toByteArray();
        // The stream's own buffer held the same bytes. Overwriting it is the one copy this code can reach.
        out.reset();
        out.writeBytes(new byte[assembled.length]);
        return assembled;
    }

    private static byte[] bytes(String ascii) {
        return ascii.getBytes(StandardCharsets.UTF_8);
    }

    /** A JSON string literal for a credential that is expected to be an identifier, escaped conservatively. */
    private static String quoted(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (char character : value.toCharArray()) {
            if (character == '"' || character == '\\') {
                quoted.append('\\').append(character);
            } else if (character < 0x20) {
                quoted.append(String.format("\\u%04x", (int) character));
            } else {
                quoted.append(character);
            }
        }
        return quoted.append('"').toString();
    }

    /** A status and a bounded body. */
    private record Exchange(int status, byte[] body) {
        void clear() {
            Arrays.fill(body, (byte) 0);
        }
    }

    /** Never the token and never the settings' credentials. */
    @Override
    public String toString() {
        return "VaultTransitClient[" + settings + "]";
    }
}
