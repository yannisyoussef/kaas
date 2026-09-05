package com.kaas.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Resolved-address classification")
class AddressPolicyTest {

    /**
     * Parses a literal into raw bytes WITHOUT resolution.
     *
     * <p>InetAddress.getByName on a literal does not hit DNS, but it does apply the JDK's own normalization —
     * which is the thing this classifier deliberately does not depend on. Going through it here is safe
     * because these are literals and because the classifier is then handed the bytes; what matters is that
     * production never asks the JDK to turn a name into an address.
     */
    private static byte[] bytesOf(String literal) {
        try {
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException impossible) {
            throw new AssertionError("A literal is not a lookup: " + literal, impossible);
        }
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
        // Ordinary internet space, which is the only thing that may be connected to.
        "8.8.8.8,             GLOBAL_UNICAST",
        "1.1.1.1,             GLOBAL_UNICAST",
        "11.0.0.7,            GLOBAL_UNICAST",
        "172.15.255.255,      GLOBAL_UNICAST",
        "172.32.0.0,          GLOBAL_UNICAST",
        "100.63.255.255,      GLOBAL_UNICAST",
        "100.128.0.0,         GLOBAL_UNICAST",
        "223.255.255.255,     GLOBAL_UNICAST",
        "198.17.255.255,      GLOBAL_UNICAST",
        "198.20.0.0,          GLOBAL_UNICAST",
        "192.0.1.255,         GLOBAL_UNICAST",
        "192.0.3.0,           GLOBAL_UNICAST",

        // Everything the proxy exists to keep a tenant away from.
        "0.0.0.0,             THIS_NETWORK",
        "0.255.255.255,       THIS_NETWORK",
        "10.0.0.1,            PRIVATE_USE",
        "10.255.255.255,      PRIVATE_USE",
        "172.16.0.1,          PRIVATE_USE",
        "172.31.255.255,      PRIVATE_USE",
        "192.168.1.1,         PRIVATE_USE",
        "192.168.255.255,     PRIVATE_USE",
        "127.0.0.1,           LOOPBACK",
        "127.255.255.255,     LOOPBACK",
        "169.254.0.1,         LINK_LOCAL",
        "169.254.169.254,     LINK_LOCAL",
        "100.64.0.1,          SHARED_ADDRESS_SPACE",
        "100.127.255.255,     SHARED_ADDRESS_SPACE",
        "224.0.0.1,           MULTICAST",
        "239.255.255.255,     MULTICAST",
        "240.0.0.1,           RESERVED",
        "255.255.255.255,     RESERVED",
        "192.0.2.1,           DOCUMENTATION",
        "198.51.100.1,        DOCUMENTATION",
        "203.0.113.1,         DOCUMENTATION",
        "198.18.0.1,          BENCHMARKING",
        "198.19.255.255,      BENCHMARKING",
        "192.0.0.1,           PROTOCOL_ASSIGNMENT",
        "192.88.99.1,         PROTOCOL_ASSIGNMENT",
    })
    void anIpv4AddressIsClassifiedByItsRange(String literal, AddressClass expected) {
        assertThat(AddressPolicy.classify(bytesOf(literal))).isEqualTo(expected);
    }

    @Test
    @DisplayName("cloud instance metadata is denied, by name as well as by range")
    void cloudMetadataIsDenied() {
        // The range rule above already covers it. This test exists separately because the consequence of
        // 169.254.169.254 becoming reachable is credential theft on most cloud providers, and a regression
        // that narrowed the link-local rule would otherwise be caught only by a boundary case whose
        // significance is not obvious to whoever is editing it.
        assertThat(AddressPolicy.classify(bytesOf("169.254.169.254")).permitted()).isFalse();
        // Not only AWS: GCP and Alibaba publish metadata on the same link-local range, and Azure's
        // 168.63.129.16 is global unicast and therefore NOT covered here. That is stated in the security
        // document as a residual risk rather than papered over with a one-address special case.
        assertThat(AddressPolicy.classify(bytesOf("169.254.169.253")).permitted()).isFalse();
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
        // These are written as the thirty-two hex digits of the address, not as text handed to
        // InetAddress.getByName. That is not pedantry: getByName collapses ::ffff:8.8.8.8 into a four-byte
        // Inet4Address, so a test written the readable way would hand the classifier four bytes and prove
        // nothing about the sixteen-byte path a AAAA record actually delivers. The first version of this test
        // did exactly that and reported GLOBAL_UNICAST for an address the proxy must refuse.

        // IPv4 written inside IPv6 keeps the meaning of its IPv4 form. Removing the embedded-address handling
        // makes every one of these report IPV6_NOT_SUPPORTED, so each turns red on that mutation rather than
        // passing for the accidental reason that IPv6 is off altogether.
        "00000000000000000000ffff7f000001, LOOPBACK",           // ::ffff:127.0.0.1
        "00000000000000000000ffff0a000001, PRIVATE_USE",        // ::ffff:10.0.0.1
        "00000000000000000000ffffa9fea9fe, LINK_LOCAL",         // ::ffff:169.254.169.254
        "00000000000000000000ffffc0a80001, PRIVATE_USE",        // ::ffff:192.168.0.1
        "0000000000000000000000007f000001, LOOPBACK",           // ::127.0.0.1, the deprecated compatible form
        "20027f000001000000000000000000ea, LOOPBACK",           // 2002:7f00:1::/48, 6to4 over loopback
        "20020a000001000000000000000000ea, PRIVATE_USE",        // 2002:a00:1::/48, 6to4 over RFC 1918
        "200100000a000001000000000000000e, PRIVATE_USE",        // 2001:0:a00:1::/48, Teredo over RFC 1918

        // The two IPv6 addresses that are not addresses of a host.
        "00000000000000000000000000000001, LOOPBACK",           // ::1
        "00000000000000000000000000000000, THIS_NETWORK",       // ::

        // Genuinely global IPv6, and IPv6 that embeds a global IPv4, are still refused: v1 carries IPv4 only,
        // and an AAAA answer is an IPv6 destination whatever its low bits spell.
        "26064700470000000000000000001111, IPV6_NOT_SUPPORTED", // 2606:4700:4700::1111
        "00000000000000000000ffff08080808, IPV6_NOT_SUPPORTED", // ::ffff:8.8.8.8
        "fd000000000000000000000000000001, IPV6_NOT_SUPPORTED", // fd00::1, unique local
        "fe800000000000000000000000000001, IPV6_NOT_SUPPORTED", // fe80::1, link local
    })
    void anIpv6AddressIsRefusedAndItsEmbeddedIpv4StillClassified(String hex, AddressClass expected) {
        assertThat(AddressPolicy.classify(sixteenBytes(hex))).isEqualTo(expected);
    }

    private static byte[] sixteenBytes(String hex) {
        assertThat(hex).hasSize(32);
        byte[] address = new byte[16];
        for (int index = 0; index < 16; index++) {
            address[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
        return address;
    }

    @Test
    @DisplayName("a sixteen-byte IPv4-mapped address is classified from its own bytes, not the JDK's reading")
    void aMappedAddressIsClassifiedFromRawBytes() {
        // getByName collapses ::ffff:127.0.0.1 to a four-byte Inet4Address on current JDKs, so the case above
        // does not actually prove the sixteen-byte path works. This hands the classifier the sixteen bytes
        // directly, which is the shape a AAAA record delivers.
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 127;
        mapped[15] = 1;
        assertThat(AddressPolicy.classify(mapped)).isEqualTo(AddressClass.LOOPBACK);

        byte[] mappedMetadata = new byte[16];
        mappedMetadata[10] = (byte) 0xff;
        mappedMetadata[11] = (byte) 0xff;
        mappedMetadata[12] = (byte) 169;
        mappedMetadata[13] = (byte) 254;
        mappedMetadata[14] = (byte) 169;
        mappedMetadata[15] = (byte) 254;
        assertThat(AddressPolicy.classify(mappedMetadata)).isEqualTo(AddressClass.LINK_LOCAL);
    }

    @Test
    @DisplayName("exactly one class is permitted, so a new class cannot default to allowed")
    void onlyGlobalUnicastIsPermitted() {
        List<AddressClass> permitted = new ArrayList<>();
        for (AddressClass candidate : AddressClass.values()) {
            if (candidate.permitted()) {
                permitted.add(candidate);
            }
        }
        // Derived from the enum rather than written out, so adding a class that is permitted by mistake fails
        // here instead of silently widening what the proxy will connect to.
        assertThat(permitted).containsExactly(AddressClass.GLOBAL_UNICAST);
    }

    @Test
    @DisplayName("every IPv4 address is classified, so no range falls through unexamined")
    void everyIpv4AddressIsClassified() {
        // Walks the whole first octet and a spread of the rest. The point is not the count but that no input
        // reaches a default nobody wrote: an unclassified address would have to become GLOBAL_UNICAST, and a
        // range accidentally reaching that default is exactly the failure an allowlist-shaped policy prevents.
        for (int first = 0; first <= 255; first++) {
            for (int second : new int[] {0, 15, 16, 31, 32, 63, 64, 88, 99, 100, 127, 128, 168, 254, 255}) {
                byte[] address = {(byte) first, (byte) second, (byte) 99, (byte) 7};
                assertThat(AddressPolicy.classify(address)).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("an address of the wrong length is an error, not a guess")
    void aMalformedAddressIsRefused() {
        assertThatThrownBy(() -> AddressPolicy.classify(new byte[5]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AddressPolicy.classify(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
