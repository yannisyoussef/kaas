package com.kaas.runner.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plaintext secrets cross a network only inside TLS; plain http is accepted only to this host's loopback. */
@DisplayName("Secret redemption transport")
class SecretTransportTests {

    @Test
    @DisplayName("https anywhere and loopback http are accepted; any other plain-http address is refused")
    void onlyEncryptedOrLocalTransportCarriesSecrets() {
        assertThat(ControlPlaneClient.carriesSecretsSafely(URI.create("https://api.internal:8443"))).isTrue();
        assertThat(ControlPlaneClient.carriesSecretsSafely(URI.create("http://localhost:8080"))).isTrue();
        assertThat(ControlPlaneClient.carriesSecretsSafely(URI.create("http://127.0.0.1:8080"))).isTrue();
        assertThat(ControlPlaneClient.carriesSecretsSafely(URI.create("http://[::1]:8080"))).isTrue();
        assertThat(ControlPlaneClient.carriesSecretsSafely(URI.create("http://api:8080"))).isFalse();
        assertThat(ControlPlaneClient.carriesSecretsSafely(URI.create("http://10.0.0.5:8080"))).isFalse();
        assertThat(ControlPlaneClient.carriesSecretsSafely(URI.create("http://localhost.example.com"))).isFalse();
    }

    @Test
    @DisplayName("a redemption over plain http to another host is refused before any request is made")
    void aPlainHttpRedemptionIsRefusedWithoutARequest() {
        // An address nothing listens on: had a request been attempted, the failure would be a connection
        // error after the retry sleep, and the sleeper below would have been called.
        ControlPlaneClient client = new ControlPlaneClient(
                HttpClient.newHttpClient(), URI.create("http://api.invalid:8080"), "Bearer x",
                Duration.ofSeconds(1), duration -> {
                    throw new AssertionError("no request, so no retry, may happen");
                });
        assertThatThrownBy(() -> client.redeemSecrets("capability", 1024))
                .isInstanceOf(ControlPlaneUnavailable.class)
                .hasMessageContaining("https or loopback");
    }
}
