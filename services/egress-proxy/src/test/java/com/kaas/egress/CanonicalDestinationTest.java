package com.kaas.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Proxy-side destination canonicalization")
class CanonicalDestinationTest {

    @Test
    @DisplayName("a canonical authority parses to the form the allowlist is compared against")
    void aCanonicalAuthorityParses() {
        CanonicalDestination destination = CanonicalDestination.parseAuthority("api.example.com:443", Scheme.HTTPS);
        assertThat(destination.host()).isEqualTo("api.example.com");
        assertThat(destination.port()).isEqualTo(443);
        assertThat(destination.canonical()).isEqualTo("api.example.com:443/HTTPS");
    }

    @Test
    @DisplayName("the scheme is part of the destination, so the same host and port differ by transport")
    void theSchemeIsPartOfTheDestination() {
        assertThat(CanonicalDestination.parseAuthority("api.example.com:443", Scheme.HTTP).canonical())
                .isNotEqualTo(CanonicalDestination.parseAuthority("api.example.com:443", Scheme.HTTPS).canonical());
    }

    @ParameterizedTest(name = "refuses \"{0}\"")
    @ValueSource(strings = {
        // Second spellings of a destination. Each would otherwise denote something the allowlist already
        // contains while comparing unequal to it — or, worse, be repaired into it by a parser being helpful.
        "API.example.com:443",
        "api.EXAMPLE.com:443",
        "api.example.com.:443",
        ".api.example.com:443",
        "api..example.com:443",
        "api.example.com:0443",

        // Forms whose meaning depends on which parser reads them.
        "user@api.example.com:443",
        "user:pass@api.example.com:443",
        "exam%70le.example.com:443",
        "[api.example.com]:443",
        "api.example.com:443:443",
        "api.example.com",
        "api.example.com:",
        ":443",
        "",

        // Wildcards and ranges, refused at parse time rather than accepted and never matched. An entry that
        // can never match is one a tenant believes is protecting them.
        "*:443",
        "*.example.com:443",
        "0.0.0.0/0:443",

        // Not a fully qualified name, which is also what removes localhost — by rule, not by blocklist.
        "localhost:443",
        "metadata:80",

        // IP literals, refused in v1: they arrive with the resolution the classifier exists to inspect
        // already performed by the caller.
        "127.0.0.1:443",
        "169.254.169.254:80",
        "10.0.0.1:443",
        "0.0.0.0:443",

        // Ports that are not a plain decimal integer in range.
        "api.example.com:0",
        "api.example.com:65536",
        "api.example.com:99999",
        "api.example.com:+443",
        "api.example.com:44 3",
        "api.example.com:0x1bb",
        "api.example.com:-1",

        // Hyphen placement, which is a real hostname rule and also a place parsers differ.
        "-api.example.com:443",
        "api-.example.com:443",
    })
    void aNonCanonicalAuthorityIsRefused(String authority) {
        assertThatThrownBy(() -> CanonicalDestination.parseAuthority(authority, Scheme.HTTPS))
                .isInstanceOf(MalformedDestination.class);
    }

    @Test
    @DisplayName("a refusal names the rule that was broken, so each rule is provable on its own")
    void aRefusalNamesTheRuleItBroke() {
        // Several rules here overlap: a trailing dot also produces an empty final label, and an authority with
        // two colons also produces a host containing a character the charset rule forbids. Asserting only
        // "refused" therefore proves less than it appears to — deleting the trailing-dot rule or searching for
        // the colon from the right leaves every such test green, because a later rule catches the same input
        // for a different reason. Both of those mutations survived a suite that asserted only the exception
        // type. Pinning the reason is what makes each rule independently killable; where a rule genuinely
        // cannot be reached on its own, that is recorded as joint coverage rather than claimed as independent.
        assertRefusal("api.example.com.:443", "trailing dot");
        assertRefusal("api..example.com:443", "empty labels");
        assertRefusal("api.example.com:443:443", "exactly one port");
        assertRefusal("API.example.com:443", "lower-case ASCII");
        assertRefusal("localhost:443", "fully qualified");
        assertRefusal("127.0.0.1:443", "not an IP literal");
        assertRefusal("api.example.com:0443", "leading zeroes");
        assertRefusal("api.example.com:44a", "decimal digits");
        assertRefusal("api.example.com", "explicit port");
        assertRefusal("-api.example.com:443", "hyphen");
        assertRefusal("a".repeat(64) + ".example.com:443", "label is at most");
    }

    private static void assertRefusal(String authority, String reason) {
        assertThatThrownBy(() -> CanonicalDestination.parseAuthority(authority, Scheme.HTTPS))
                .isInstanceOf(MalformedDestination.class)
                .hasMessageContaining(reason);
    }

    @Test
    @DisplayName("control characters in an authority are refused rather than stripped")
    void controlCharactersAreRefused() {
        // Written here rather than in the @ValueSource table because a NUL or a CR inside a source annotation
        // is invisible to a reader and easy to lose in an edit. These are the smuggling primitives: a parser
        // that truncates at NUL or splits at CR sees a different destination than the one authorized.
        for (String hostile : new String[] {
            "api.example.com\u0000.evil.example:443",
            "api.example.com\r\nHost: evil.example:443",
            "api.example.com\n:443",
            "api.example.com\t:443",
            "api.example.com :443",
            " api.example.com:443",
            "api.exa\u00admple.com:443",
            "api.exämple.com:443",
            "api.xn--exmple-cua.com\u200b:443",
        }) {
            assertThatThrownBy(() -> CanonicalDestination.parseAuthority(hostile, Scheme.HTTPS))
                    .as("authority %s", hostile.replace("\r", "\\r").replace("\n", "\\n").replace("\u0000", "\\0"))
                    .isInstanceOf(MalformedDestination.class);
        }
    }

    @Test
    @DisplayName("punycode is carried through, because the tenant supplies it already encoded")
    void punycodeIsAccepted() {
        // The contract refuses Unicode rather than decoding it: two IDNA implementations at different library
        // versions is the disagreement the whole document exists to prevent. An already-encoded name is
        // ordinary ASCII and needs no special handling — which is the point.
        assertThat(CanonicalDestination.parseAuthority("xn--exmple-cua.com:443", Scheme.HTTPS).host())
                .isEqualTo("xn--exmple-cua.com");
    }

    @Test
    @DisplayName("length limits hold on both sides of each boundary")
    void lengthLimitsHold() {
        String longestLabel = "a".repeat(63);
        assertThat(CanonicalDestination.parseAuthority(longestLabel + ".example.com:443", Scheme.HTTPS).host())
                .isEqualTo(longestLabel + ".example.com");
        assertThatThrownBy(() ->
                        CanonicalDestination.parseAuthority("a".repeat(64) + ".example.com:443", Scheme.HTTPS))
                .isInstanceOf(MalformedDestination.class);

        // 253 characters exactly: three 63-character labels, one 61-character label, three dots.
        String longestHost = ("a".repeat(63) + ".").repeat(3) + "a".repeat(61);
        assertThat(longestHost).hasSize(253);
        assertThat(CanonicalDestination.parseAuthority(longestHost + ":443", Scheme.HTTPS).host())
                .isEqualTo(longestHost);
        assertThatThrownBy(() -> CanonicalDestination.parseAuthority(longestHost + "a:443", Scheme.HTTPS))
                .isInstanceOf(MalformedDestination.class);
    }

    @Test
    @DisplayName("a numeric-looking name that is not a dotted quad stays a name the resolver must answer for")
    void aNumericLookingNameIsStillAName() {
        // 1.2.3.4.5 is not an address in any notation, so it is a hostname. Nothing in the proxy hands a
        // hostname to a resolver that would reinterpret it as an address, and whatever DNS returns for it is
        // classified before a socket is opened — so treating it as a name is safe rather than merely permitted.
        assertThat(CanonicalDestination.parseAuthority("1.2.3.4.5:443", Scheme.HTTPS).host())
                .isEqualTo("1.2.3.4.5");
        // A three-label numeric form likewise. 10.0.1 is a shortened address to inet_aton and a name to DNS;
        // this proxy only ever uses DNS.
        assertThat(CanonicalDestination.parseAuthority("10.0.1:443", Scheme.HTTPS).host()).isEqualTo("10.0.1");
    }

    @Test
    @DisplayName("a null scheme is refused, so a destination is never half-specified")
    void aNullSchemeIsRefused() {
        assertThatThrownBy(() -> CanonicalDestination.parseAuthority("api.example.com:443", null))
                .isInstanceOf(MalformedDestination.class);
    }
}
