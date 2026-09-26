package com.kaas.api.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.controlplane.domain.PinnedSecretBinding;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The redemption's exact-set comparison, driven directly.
 *
 * <p>Every end-to-end path writes the capability's scope from the same snapshot it is later compared with, so
 * no integration test can produce a disagreement — which means none could notice the comparison being deleted.
 * This one supplies the disagreements: extra, missing, a different version, a different reference, a duplicate
 * key, and nothing at all.
 */
class SecretScopeExactnessTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ALPHA = UUID.randomUUID();
    private static final UUID BETA = UUID.randomUUID();

    private static ExecutionAuthorizationRepository.SecretMaterial scope(String key, UUID reference, int version) {
        return new ExecutionAuthorizationRepository.SecretMaterial(ORG, PROJECT, key, reference, version, false, "vault:v1:AAAA");
    }

    private static final List<PinnedSecretBinding> PINNED = List.of(
            new PinnedSecretBinding("ALPHA", ALPHA, 1), new PinnedSecretBinding("BETA", BETA, 3));

    @Test
    void theExactPinnedSetIsAccepted() {
        assertThat(SecretCapabilityService.exactlyTheSnapshot(
                        List.of(scope("BETA", BETA, 3), scope("ALPHA", ALPHA, 1)), PINNED))
                .isTrue();
    }

    @Test
    void anythingElseIsRefused() {
        assertThat(SecretCapabilityService.exactlyTheSnapshot(
                        List.of(scope("ALPHA", ALPHA, 1), scope("BETA", BETA, 3), scope("GAMMA", UUID.randomUUID(), 1)),
                        PINNED))
                .as("an extra secret").isFalse();
        assertThat(SecretCapabilityService.exactlyTheSnapshot(List.of(scope("ALPHA", ALPHA, 1)), PINNED))
                .as("a missing secret").isFalse();
        assertThat(SecretCapabilityService.exactlyTheSnapshot(
                        List.of(scope("ALPHA", ALPHA, 1), scope("BETA", BETA, 4)), PINNED))
                .as("the latest version rather than the pinned one").isFalse();
        assertThat(SecretCapabilityService.exactlyTheSnapshot(
                        List.of(scope("ALPHA", ALPHA, 1), scope("BETA", UUID.randomUUID(), 3)), PINNED))
                .as("another reference under the same key").isFalse();
        assertThat(SecretCapabilityService.exactlyTheSnapshot(
                        List.of(scope("ALPHA", ALPHA, 1), scope("ALPHA", ALPHA, 1)),
                        List.of(new PinnedSecretBinding("ALPHA", ALPHA, 1), new PinnedSecretBinding("ALPHA", ALPHA, 1))))
                .as("a duplicate key, last-wins nowhere").isFalse();
        assertThat(SecretCapabilityService.exactlyTheSnapshot(List.of(), List.of()))
                .as("an empty scope authorizes nothing").isFalse();
        assertThat(SecretCapabilityService.exactlyTheSnapshot(
                        List.of(scope("ALPHA", ALPHA, 1)), List.of(new PinnedSecretBinding("ALPHA", ALPHA, null))))
                .as("a snapshot with no pinned version").isFalse();
    }
}
