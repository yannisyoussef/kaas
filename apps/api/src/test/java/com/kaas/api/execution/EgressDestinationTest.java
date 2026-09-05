package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.execution.domain.EgressDestination;
import com.kaas.api.execution.domain.EgressScheme;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Canonicalization, which is a security control rather than tidiness.
 *
 * <p>Every refusal below removes either a second spelling of one destination — which would let a tenant
 * believe an entry covers traffic it does not — or a spelling two parsers would read differently, which is how
 * the proxy comes to permit what the control plane thought it had denied.
 */
class EgressDestinationTest {

    @Test
    @DisplayName("a canonical destination is accepted and compares byte-for-byte")
    void canonicalDestinationsAreAccepted() {
        var destination = new EgressDestination("api.example.com", 443, EgressScheme.HTTPS);
        assertThat(destination.canonical()).isEqualTo("api.example.com:443/HTTPS");

        // Scheme is part of the identity, not derived from the port. Authorizing one has not authorized the
        // other, and the two must not compare equal.
        assertThat(new EgressDestination("api.example.com", 443, EgressScheme.HTTP).canonical())
                .isNotEqualTo(destination.canonical());
    }

    @Test
    @DisplayName("every second spelling of one destination is refused rather than rewritten")
    void secondSpellingsAreRefused() {
        // Refused, not normalized. Accepting these and storing something else would mean the destination the
        // tenant wrote and the destination the platform enforces are different strings, invisibly.
        List<String> notCanonical = List.of(
                "API.example.com",      // upper case
                "api.example.com.",     // trailing dot
                "api..example.com",     // empty label
                ".api.example.com",     // leading empty label
                "api.example.com ",     // trailing whitespace
                "exam%70le.com",        // percent-encoded
                "user@example.com",     // userinfo
                "[example.com]",        // bracketed
                "exämple.com",          // unicode, not punycode
                "-api.example.com",     // label starts with a hyphen
                "api-.example.com");    // label ends with a hyphen

        for (String host : notCanonical) {
            assertThatThrownBy(() -> new EgressDestination(host, 443, EgressScheme.HTTPS))
                    .as("%s must be refused rather than silently canonicalized", host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("wildcards and CIDR are refused at parse time, not matched and denied later")
    void wildcardsAreRefused() {
        // An entry that can never match is worse than a rejected one: a tenant sees it stored and believes it
        // is working.
        for (String host : List.of("*", "*.example.com", "0.0.0.0/0", "::/0", "example.com/*")) {
            assertThatThrownBy(() -> new EgressDestination(host, 443, EgressScheme.HTTPS))
                    .as("%s must be refused at parse time", host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("IP literals are refused in v1, because they skip the resolution the classifier inspects")
    void ipLiteralsAreRefused() {
        // The address classifier runs on what a resolution returned. A literal has no resolution, so it would
        // reach connect without passing the check the classifier exists to perform.
        for (String host : List.of("203.0.113.10", "127.0.0.1", "169.254.169.254", "10.0.0.1")) {
            assertThatThrownBy(() -> new EgressDestination(host, 443, EgressScheme.HTTPS))
                    .as("%s is an IP literal and must be refused", host)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hostname");
        }
        // IPv6 forms are already excluded by the character rule; asserted so that stays true.
        for (String host : List.of("::1", "fe80::1", "2001:db8::1")) {
            assertThatThrownBy(() -> new EgressDestination(host, 443, EgressScheme.HTTPS))
                    .as("%s must be refused", host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a single-label host is refused, which also removes localhost")
    void singleLabelHostsAreRefused() {
        for (String host : List.of("localhost", "metadata", "internal")) {
            assertThatThrownBy(() -> new EgressDestination(host, 80, EgressScheme.HTTP))
                    .as("%s is not a fully qualified destination", host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the port is explicit and bounded")
    void portsAreExplicitAndBounded() {
        for (int port : List.of(0, -1, 65536, 99999)) {
            assertThatThrownBy(() -> new EgressDestination("api.example.com", port, EgressScheme.HTTPS))
                    .as("port %d must be refused", port)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new EgressDestination("api.example.com", 1, EgressScheme.HTTP).port()).isEqualTo(1);
        assertThat(new EgressDestination("api.example.com", 65535, EgressScheme.HTTP).port()).isEqualTo(65535);
    }

    @Test
    @DisplayName("length limits hold at the label and the whole name")
    void lengthLimitsHold() {
        String longLabel = "a".repeat(64);
        assertThatThrownBy(() -> new EgressDestination(longLabel + ".example.com", 443, EgressScheme.HTTPS))
                .isInstanceOf(IllegalArgumentException.class);
        // 63 is the boundary and must be accepted, or the limit is off by one in the safe-looking direction.
        assertThat(new EgressDestination("a".repeat(63) + ".example.com", 443, EgressScheme.HTTPS).host())
                .startsWith("a");

        String longHost = ("a".repeat(50) + ".").repeat(5) + "example.com";
        assertThatThrownBy(() -> new EgressDestination(longHost, 443, EgressScheme.HTTPS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
