package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the execution's egress credential goes: into the engine frame and the redactor's list, in both the form
 * it is delivered in and the form the platform's own proxy configuration sends it in, and out of the container's
 * environment. Docker-free; the secret-bearing suite measures the same things against a real daemon.
 */
@DisplayName("Engine input and the egress credential")
class EngineInputTests {

    private static final String TOKEN = "egress-" + UUID.randomUUID();

    private static EngineInput withEgress() {
        return EngineInput.of(
                List.of(new EngineInput.Secret("API_TOKEN", "v".getBytes(StandardCharsets.UTF_8))),
                new EngineInput.Egress("10.0.0.2", 3128, TOKEN));
    }

    @Test
    @DisplayName("the credential is redacted raw and in the Basic form karate-config.js sends it in")
    void theCredentialIsRedactedInBothForms() {
        try (EngineInput input = withEgress()) {
            List<String> redactable = input.redactable().stream()
                    .map(bytes -> new String(bytes, StandardCharsets.US_ASCII)).toList();
            String basic = Base64.getEncoder().encodeToString(("kaas:" + TOKEN).getBytes(StandardCharsets.US_ASCII));
            assertThat(redactable).contains(TOKEN, basic);
        }
    }

    @Test
    @DisplayName("an engine's container environment carries no egress credential; a probe's still does")
    void anEngineEnvironmentCarriesNoCredential() {
        Map<String, String> egress = Map.of(
                "KAAS_EGRESS_PROXY_HOST", "10.0.0.2", "KAAS_EGRESS_CAPABILITY", TOKEN);
        SandboxSecurityProfile networked = SandboxSecurityProfile.version1OnNetwork(
                "image@sha256:" + "0".repeat(64), ExecutionNetwork.NAME_PREFIX + "x", egress,
                ExecutionRuntimeType.DOCKER);
        byte[] frame = {1};
        try (EngineInput input = withEgress()) {
            SandboxSecurityProfile engine = SandboxSecurityProfile.withSource(
                    networked, new SandboxSecurityProfile.SourceDelivery(frame, 1 << 20, input));
            assertThat(engine.environment()).doesNotContainKey("KAAS_EGRESS_CAPABILITY")
                    .containsEntry("KAAS_EGRESS_PROXY_HOST", "10.0.0.2");
            assertThat(engine.environment().values()).doesNotContain(TOKEN);
        }
        SandboxSecurityProfile probe = SandboxSecurityProfile.withSource(
                networked, new SandboxSecurityProfile.SourceDelivery(frame, 1 << 20));
        assertThat(probe.environment()).containsEntry("KAAS_EGRESS_CAPABILITY", TOKEN);
    }

    @Test
    @DisplayName("a malformed egress endpoint is refused before any frame is built")
    void aMalformedEndpointIsRefused() {
        assertThatThrownBy(() -> EngineInput.of(List.of(), new EngineInput.Egress("bad host", 3128, TOKEN)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
