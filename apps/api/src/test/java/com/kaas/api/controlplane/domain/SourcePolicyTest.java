package com.kaas.api.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourcePolicyTest {
    @Test
    void measuresUtf8BytesAndPreservesLineEndings() {
        String lf = "Feature: café\n";
        String crlf = "Feature: café\r\n";

        assertThat(SourcePolicy.validateAndDigest(lf)).isNotEqualTo(SourcePolicy.validateAndDigest(crlf));
        assertThat(lf.getBytes(StandardCharsets.UTF_8).length).isNotEqualTo(lf.length());
    }

    @Test
    void acceptsExactLimitAndRejectsOneByteOverNulControlsAndUnpairedSurrogates() {
        assertThat(SourcePolicy.validateAndDigest("a".repeat(SourcePolicy.MAX_SOURCE_BYTES)))
                .startsWith("sha256:");
        assertThatThrownBy(() -> SourcePolicy.validateAndDigest("a".repeat(SourcePolicy.MAX_SOURCE_BYTES + 1)))
                .isInstanceOf(SourcePolicy.SourceTooLargeException.class);
        assertThatThrownBy(() -> SourcePolicy.validateAndDigest("a\0b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourcePolicy.validateAndDigest("a\u0001b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourcePolicy.validateAndDigest("\uD800")).isInstanceOf(IllegalArgumentException.class);
    }
}
