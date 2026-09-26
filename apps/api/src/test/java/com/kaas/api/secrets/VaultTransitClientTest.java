package com.kaas.api.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.secrets.domain.SecretFailure;
import com.kaas.api.secrets.domain.SecretLimits;
import com.kaas.api.secrets.domain.SecretProviderException;
import com.kaas.api.secrets.domain.SecretTransit;
import com.kaas.api.secrets.domain.TransitContext;
import com.kaas.api.secrets.infrastructure.VaultTransitClient;
import com.kaas.api.secrets.infrastructure.VaultTransitSettings;
import com.kaas.api.testing.VaultTransitFixture;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Transit client against a real Vault, provisioned with the production key shape and an AppRole whose
 * policy allows encrypt and decrypt on one key and nothing else.
 *
 * <p>Every assertion about a plaintext is a boolean or a category. None of them compares a secret through an
 * assertion that would print it on failure — that is the test-report leak this slice exists to close, and a
 * test that caused it would be reporting on itself.
 */
class VaultTransitClientTest {

    private static VaultTransitFixture vault;

    private static final SecureRandom RANDOM = new SecureRandom();

    @BeforeAll
    static void startVault() {
        vault = new VaultTransitFixture();
    }

    @AfterAll
    static void stopVault() {
        vault.close();
    }

    private static VaultTransitClient client(VaultTransitFixture against) {
        return new VaultTransitClient(new VaultTransitSettings(
                URI.create(against.address()),
                against.roleId(),
                against.secretId(),
                against.caFile(),
                VaultTransitFixture.TRANSIT_KEY,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)));
    }

    private static TransitContext tenant() {
        return TransitContext.of(UUID.randomUUID(), UUID.randomUUID());
    }

    /** A value generated here, never written in source, compared by a boolean. */
    private static byte[] generated(String prefix) {
        byte[] entropy = new byte[24];
        RANDOM.nextBytes(entropy);
        return (prefix + HexFormat.of().formatHex(entropy)).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every value survives encryption and decryption byte for byte, including multiline and Unicode")
    void valuesRoundTripExactly() throws Exception {
        SecretTransit transit = client(vault);
        TransitContext context = tenant();
        List<byte[]> values = List.of(
                generated("token-"),
                ("é中𝄞 " + new String(generated("u-"), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),
                ("-----BEGIN KEY-----\r\n" + new String(generated("a"), StandardCharsets.UTF_8)
                                + "\r\n-----END KEY-----\r\n").getBytes(StandardCharsets.UTF_8),
                ("line one\nline two\n" + new String(generated("b"), StandardCharsets.UTF_8) + "\n")
                        .getBytes(StandardCharsets.UTF_8),
                ("  padded both sides " + new String(generated("c"), StandardCharsets.UTF_8) + "  ")
                        .getBytes(StandardCharsets.UTF_8),
                maximal());
        for (byte[] value : values) {
            var encrypted = transit.encrypt(context, value.clone());
            assertThat(encrypted.ciphertext()).startsWith("vault:v1:");
            assertThat(encrypted.keyName()).isEqualTo(VaultTransitFixture.TRANSIT_KEY);
            assertThat(encrypted.keyVersion()).isEqualTo(1);
            byte[] decrypted = transit.decrypt(context, encrypted.ciphertext());
            assertThat(Arrays.equals(decrypted, value)).as("the exact bytes came back").isTrue();
            // Independent evidence that the stored form is a real Transit ciphertext under exactly this context,
            // obtained through Vault itself rather than through the client under test.
            assertThat(Arrays.equals(
                            vault.decryptAsOperator(
                                    encrypted.ciphertext(), context.organizationId(), context.projectId()),
                            value))
                    .as("Vault itself decrypts it to the same bytes")
                    .isTrue();
            assertThat(encrypted.toString()).doesNotContain(encrypted.ciphertext());
        }
    }

    private static byte[] maximal() {
        byte[] value = new byte[SecretLimits.MAX_VALUE_BYTES];
        Arrays.fill(value, (byte) 'x');
        byte[] tail = generated("");
        System.arraycopy(tail, 0, value, 0, tail.length);
        return value;
    }

    @Test
    @DisplayName("a ciphertext does not decrypt under another project's context, even in the same organization")
    void anotherTenantsContextCannotDecrypt() throws Exception {
        SecretTransit transit = client(vault);
        UUID organization = UUID.randomUUID();
        TransitContext owner = TransitContext.of(organization, UUID.randomUUID());
        TransitContext sibling = TransitContext.of(organization, UUID.randomUUID());
        TransitContext stranger = TransitContext.of(UUID.randomUUID(), owner.projectId());

        String ciphertext = transit.encrypt(owner, generated("s-")).ciphertext();

        for (TransitContext wrong : List.of(sibling, stranger)) {
            assertThatThrownBy(() -> transit.decrypt(wrong, ciphertext))
                    .isInstanceOf(SecretProviderException.class)
                    .extracting(failure -> ((SecretProviderException) failure).failure())
                    .isEqualTo(SecretFailure.SECRET_ACCESS_DENIED);
            assertThat(vault.operatorCanDecrypt(ciphertext, wrong.organizationId(), wrong.projectId()))
                    .as("not even the operator can decrypt it under the wrong context: the isolation is the key's")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a value that is empty, oversized or not UTF-8 is refused before Vault is contacted")
    void invalidValuesNeverReachTheProvider() {
        // Pointed at a port nothing listens on. If validation happened after the call, these would all report
        // PROVIDER_UNAVAILABLE; reporting the value's own problem proves Vault was never asked.
        SecretTransit unreachable = new VaultTransitClient(new VaultTransitSettings(
                URI.create("https://localhost:1"), "r", "s", vault.caFile(), VaultTransitFixture.TRANSIT_KEY,
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
        TransitContext context = tenant();

        assertFailure(() -> unreachable.encrypt(context, new byte[0]), SecretFailure.SECRET_VALUE_INVALID);
        assertFailure(() -> unreachable.encrypt(context, new byte[SecretLimits.MAX_VALUE_BYTES + 1]),
                SecretFailure.SECRET_VALUE_TOO_LARGE);
        assertFailure(() -> unreachable.encrypt(context, new byte[] {(byte) 0xc3, (byte) 0x28}),
                SecretFailure.SECRET_VALUE_INVALID);
        assertFailure(() -> unreachable.encrypt(context, new byte[] {(byte) 0xe2, (byte) 0x82}),
                SecretFailure.SECRET_VALUE_INVALID);
        // And a valid value against the unreachable address is an unavailable provider, not a hang.
        assertFailure(() -> unreachable.encrypt(context, generated("v-")), SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        assertFailure(() -> unreachable.decrypt(context, "not-a-ciphertext"), SecretFailure.SECRET_VALUE_INVALID);
    }

    @Test
    @DisplayName("a token revoked early is replaced by a fresh AppRole login, transparently")
    void aRevokedTokenIsReplacedByAFreshLogin() throws Exception {
        SecretTransit transit = client(vault);
        TransitContext context = tenant();
        String ciphertext = transit.encrypt(context, generated("r-")).ciphertext();

        vault.revokeIssuedTokens();

        byte[] decrypted = transit.decrypt(context, ciphertext);
        assertThat(decrypted).isNotEmpty();
        Arrays.fill(decrypted, (byte) 0);
    }

    @Test
    @DisplayName("Transit key rotation is not a tenant rotation: old ciphertext still decrypts, new uses the new key")
    void transitKeyRotationKeepsOldCiphertextDecryptable() throws Exception {
        try (VaultTransitFixture isolated = new VaultTransitFixture()) {
            SecretTransit transit = client(isolated);
            TransitContext context = tenant();
            byte[] value = generated("k-");
            String before = transit.encrypt(context, value.clone()).ciphertext();

            isolated.rotateTransitKey();

            var after = transit.encrypt(context, value.clone());
            assertThat(after.keyVersion()).isEqualTo(2);
            assertThat(after.ciphertext()).startsWith("vault:v2:");
            assertThat(Arrays.equals(transit.decrypt(context, before), value)).isTrue();
            assertThat(Arrays.equals(transit.decrypt(context, after.ciphertext()), value)).isTrue();
        }
    }

    @Test
    @DisplayName("a sealed Vault, a lost AppRole and a stopped Vault are each reported as their own category")
    void providerFailuresAreCategorised() throws Exception {
        try (VaultTransitFixture isolated = new VaultTransitFixture()) {
            SecretTransit transit = client(isolated);
            TransitContext context = tenant();
            String ciphertext = transit.encrypt(context, generated("f-")).ciphertext();

            isolated.destroySecretIds();
            isolated.revokeIssuedTokens();
            assertFailure(() -> transit.decrypt(context, ciphertext), SecretFailure.SECRET_PROVIDER_AUTH_FAILED);

            isolated.stop();
            assertFailure(() -> transit.decrypt(context, ciphertext), SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        }
        try (VaultTransitFixture sealed = new VaultTransitFixture()) {
            SecretTransit transit = client(sealed);
            TransitContext context = tenant();
            String ciphertext = transit.encrypt(context, generated("z-")).ciphertext();
            sealed.seal();
            assertFailure(() -> transit.decrypt(context, ciphertext), SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("a key that is not derived, and so would ignore the tenant context, is refused before any use")
    void aKeyThatDoesNotSeparateTenantsIsRefused() {
        // The same Vault, AppRole and permissions; only the key differs. Vault would happily encrypt and decrypt
        // with it -- which is exactly the hazard: the context would be accepted and ignored.
        SecretTransit nonDerived = new VaultTransitClient(new VaultTransitSettings(
                URI.create(vault.address()), vault.roleId(), vault.secretId(), vault.caFile(),
                VaultTransitFixture.NON_DERIVED_KEY, Duration.ofSeconds(2), Duration.ofSeconds(5)));
        assertFailure(() -> nonDerived.encrypt(tenant(), generated("n-")), SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        assertFailure(() -> nonDerived.decrypt(tenant(), "vault:v1:AAAA"), SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("a Vault whose certificate is not signed by the configured CA is an unavailable provider")
    void anUntrustedCertificateIsRefused() throws Exception {
        Path otherCa = Files.createTempFile("kaas-other-ca-", ".pem");
        try {
            Files.writeString(otherCa, someOtherCertificate());
            SecretTransit untrusting = new VaultTransitClient(new VaultTransitSettings(
                    URI.create(vault.address()), vault.roleId(), vault.secretId(), otherCa,
                    VaultTransitFixture.TRANSIT_KEY, Duration.ofSeconds(2), Duration.ofSeconds(5)));
            assertFailure(() -> untrusting.encrypt(tenant(), generated("t-")),
                    SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
        } finally {
            Files.deleteIfExists(otherCa);
        }
    }

    /** Any certificate other than the test Vault's CA: the first one in the JDK's own trust store. */
    private static String someOtherCertificate() throws Exception {
        KeyStore cacerts = KeyStore.getInstance(
                Path.of(System.getProperty("java.home"), "lib", "security", "cacerts").toFile(),
                "changeit".toCharArray());
        var certificate = cacerts.getCertificate(cacerts.aliases().nextElement());
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    @Test
    @DisplayName("the Transit context is exactly org:<uuid>/project:<uuid>, lowercase, and nothing else")
    void theContextEncodingIsFixed() {
        TransitContext context = TransitContext.of(
                UUID.fromString("0F8FAD5B-D9CB-469F-A165-70867728950E"),
                UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7"));
        assertThat(new String(context.bytes(), StandardCharsets.US_ASCII))
                .isEqualTo("org:0f8fad5b-d9cb-469f-a165-70867728950e/project:7c9e6679-7425-40de-944b-e07fc1f90ae7");
        assertThat(context.base64())
                .isEqualTo("b3JnOjBmOGZhZDViLWQ5Y2ItNDY5Zi1hMTY1LTcwODY3NzI4OTUwZS9wcm9qZWN0OjdjOWU2Njc5LTc0MjUtNDBkZS05NDRiLWUwN2ZjMWY5MGFlNw==");
    }

    @Test
    @DisplayName("the settings refuse plaintext HTTP and never print a credential")
    void settingsAreStrictAndQuiet() {
        assertThatThrownBy(() -> new VaultTransitSettings(
                        URI.create("http://vault:8200"), "r", "s", vault.caFile(), "k",
                        Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .hasMessageContaining("https");
        var settings = new VaultTransitSettings(
                URI.create("https://vault:8200"), "role-canary", "secret-canary", vault.caFile(), "k",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThat(settings.toString()).doesNotContain("role-canary").doesNotContain("secret-canary");
        assertThat(new VaultTransitClient(settings).toString())
                .doesNotContain("role-canary").doesNotContain("secret-canary");
    }

    @Test
    @DisplayName("a provider failure carries its category and nothing else: no cause, no message, no trace")
    void failuresCarryNoCause() {
        var failure = new SecretProviderException(SecretFailure.SECRET_ACCESS_DENIED);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).isEqualTo("SECRET_ACCESS_DENIED");
        assertThat(failure.getStackTrace()).isEmpty();
    }

    private interface Operation {
        Object run() throws Exception;
    }

    private static void assertFailure(Operation operation, SecretFailure expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(SecretProviderException.class)
                .extracting(failure -> ((SecretProviderException) failure).failure())
                .isEqualTo(expected);
    }
}
