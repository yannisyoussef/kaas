package com.kaas.api.outbox.infrastructure;

import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when a non-loopback broker is configured with the credential this repository publishes.
 *
 * <p>AMQP PLAIN sends the password in the clear, so a deployment that sets the host but forgets the password
 * would otherwise authenticate against a real broker with a value anyone can read here. The OIDC configuration
 * fails closed the same way, using non-routable defaults; this keeps the broker consistent with it.
 */
@Component
class BrokerCredentialGuard implements InitializingBean {
    private static final String LOCAL_ONLY_PASSWORD = "kaas-local-only";
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "localhost");

    private final String host;
    private final String password;
    private final boolean sslEnabled;

    BrokerCredentialGuard(
            @Value("${spring.rabbitmq.host}") String host,
            @Value("${spring.rabbitmq.password}") String password,
            @Value("${spring.rabbitmq.ssl.enabled}") boolean sslEnabled) {
        this.host = host;
        this.password = password;
        this.sslEnabled = sslEnabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (LOOPBACK.contains(host)) {
            return;
        }
        if (LOCAL_ONLY_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                    "A remote broker is configured with the local development password. Set KAAS_RABBIT_PASSWORD.");
        }
        if (!sslEnabled) {
            throw new IllegalStateException(
                    "A remote broker requires TLS; AMQP PLAIN would send the credential in the clear.");
        }
    }
}
