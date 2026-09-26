package com.kaas.api.secrets.infrastructure;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Where Vault is and how the control plane authenticates to it.
 *
 * <p>Environment-driven, through {@code KAAS_VAULT_ADDR}, {@code KAAS_VAULT_ROLE_ID}, {@code KAAS_VAULT_SECRET_ID},
 * {@code KAAS_VAULT_CACERT} and {@code KAAS_VAULT_TRANSIT_KEY}. None has a default, deliberately: a default
 * address is a guess about somebody's network and a default key name is a guess about somebody's Vault, and
 * either guess being wrong should be a startup failure rather than a Transit call to the wrong place.
 *
 * <p>Only the control plane ever holds these. The runner, the sandbox, the proxy and the engine are never given
 * an AppRole, a token, or a network route to Vault — that is the Operations contract, and it is why the runner
 * receives values through a capability redemption instead of fetching them.
 *
 * <p>{@link #toString()} never prints the role or secret id.
 */
public record VaultTransitSettings(
        URI address,
        String roleId,
        String secretId,
        Path caCertificate,
        String transitKey,
        Duration connectTimeout,
        Duration requestTimeout) {

    private static final Pattern KEY_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");

    public VaultTransitSettings {
        if (address == null || !"https".equals(address.getScheme()) || address.getHost() == null) {
            // TLS is not optional and there is no "insecure" switch. A plaintext hop to the key service would
            // carry every tenant secret the platform writes and every value it resolves.
            throw new IllegalStateException("KAAS_VAULT_ADDR must be an https:// URI.");
        }
        if (address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null
                || !(address.getRawPath() == null || address.getRawPath().isEmpty() || "/".equals(address.getRawPath()))) {
            throw new IllegalStateException("KAAS_VAULT_ADDR must name a host and port and nothing else.");
        }
        if (roleId == null || roleId.isBlank() || secretId == null || secretId.isBlank()) {
            throw new IllegalStateException("KAAS_VAULT_ROLE_ID and KAAS_VAULT_SECRET_ID are both required.");
        }
        if (caCertificate == null || !Files.isRegularFile(caCertificate)) {
            throw new IllegalStateException("KAAS_VAULT_CACERT must name a readable CA certificate file.");
        }
        if (transitKey == null || !KEY_NAME.matcher(transitKey).matches()) {
            throw new IllegalStateException("KAAS_VAULT_TRANSIT_KEY must be a Transit key name.");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException("The Vault connect timeout must be positive and at most 30 seconds.");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            // Bounded, because a decryption happens while an execution's authority is ticking down, and a
            // provider call that could outlast the lease would deliver plaintext to an assignment that no
            // longer exists -- which the post-decryption revalidation would catch, but only after the fact.
            throw new IllegalStateException("The Vault request timeout must be positive and at most 30 seconds.");
        }
    }

    @Override
    public String toString() {
        return "VaultTransitSettings[address=" + address + ", roleId=<redacted>, secretId=<redacted>, caCertificate="
                + caCertificate + ", transitKey=" + transitKey + "]";
    }
}
