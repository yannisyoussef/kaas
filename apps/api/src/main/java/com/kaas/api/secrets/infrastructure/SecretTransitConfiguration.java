package com.kaas.api.secrets.infrastructure;

import com.kaas.api.secrets.domain.SecretTransit;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the secret provider from deployment configuration.
 *
 * <p>Three states, and only two of them start:
 *
 * <ul>
 *   <li><strong>Nothing configured</strong> — {@link UnconfiguredSecretTransit}. Secrets cannot be written and a
 *       secret-bearing run is refused; every secret-free path is exactly as it was.</li>
 *   <li><strong>Everything configured</strong> — {@link VaultTransitClient}, validated at startup: the address
 *       must be https, the CA file must parse, the key name must be a key name.</li>
 *   <li><strong>Some of it configured</strong> — the application refuses to start. A deployment that set an
 *       address and forgot the CA did not mean "no provider", and silently becoming one would turn an
 *       operator's typo into every secret-bearing run failing at execution time instead of one failure at
 *       deployment time.</li>
 * </ul>
 *
 * <p>This replaces the earlier arrangement in which no configuration could ever select a real provider. That
 * arrangement was right while no provider existed; the Operations contract now fixes the provider and its
 * configuration surface, and a real provider is selected by the five variables that contract names.
 */
@Configuration(proxyBeanMethods = false)
public class SecretTransitConfiguration {

    @Bean
    SecretTransit secretTransit(
            @Value("${kaas.secrets.vault.address:}") String address,
            @Value("${kaas.secrets.vault.role-id:}") String roleId,
            @Value("${kaas.secrets.vault.secret-id:}") String secretId,
            @Value("${kaas.secrets.vault.ca-cert:}") String caCertificate,
            @Value("${kaas.secrets.vault.transit-key:}") String transitKey,
            @Value("${kaas.secrets.vault.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${kaas.secrets.vault.request-timeout:PT5S}") Duration requestTimeout) {
        long present = Stream.of(address, roleId, secretId, caCertificate, transitKey)
                .filter(value -> value != null && !value.isBlank())
                .count();
        if (present == 0) {
            return new UnconfiguredSecretTransit();
        }
        if (present != 5) {
            throw new IllegalStateException(
                    "Vault Transit is partially configured. Set all of KAAS_VAULT_ADDR, KAAS_VAULT_ROLE_ID,"
                            + " KAAS_VAULT_SECRET_ID, KAAS_VAULT_CACERT and KAAS_VAULT_TRANSIT_KEY, or none of them.");
        }
        return new VaultTransitClient(new VaultTransitSettings(
                URI.create(address.strip()),
                roleId.strip(),
                secretId.strip(),
                Path.of(caCertificate.strip()),
                transitKey.strip(),
                connectTimeout,
                requestTimeout));
    }
}
