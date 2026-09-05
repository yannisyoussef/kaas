package com.kaas.egress;

/**
 * Decides what a resolved address is. This runs after DNS and before any socket is opened.
 *
 * <p><strong>The policy is an allowlist of one class, not a blocklist of many.</strong> Global unicast is
 * permitted and everything else is refused by default. A blocklist has to stay complete against a registry
 * that changes and against whatever range a future deployment turns out to sit in; the failure mode of an
 * incomplete blocklist is a permitted connection to something nobody meant to expose. The failure mode of an
 * incomplete allowlist is a refused connection, which is visible and safe.
 *
 * <p><strong>Classification is done on raw bytes, never on a parsed {@code InetAddress}.</strong> The JDK
 * normalizes an IPv4-mapped IPv6 address into an {@code Inet4Address}, and it has not always done so
 * identically across versions. A classifier written against {@code instanceof Inet6Address} would therefore be
 * asking the JDK a question whose answer is a JDK property rather than a property of the answer that came back
 * over the wire. Sixteen bytes are sixteen bytes here.
 *
 * <h2>IPv6</h2>
 *
 * <p>v1 refuses IPv6 egress outright rather than shipping a half-verified global-unicast classifier for it.
 * That is a narrowing, and it is fail-closed: an AAAA-only destination does not work, which is a visible
 * failure, rather than working through a rule nobody has tested.
 *
 * <p>Even so, an address that embeds IPv4 inside IPv6 — {@code ::ffff:127.0.0.1}, {@code ::127.0.0.1}, a 6to4
 * {@code 2002::/16} address, a Teredo {@code 2001::/32} address — is decoded and its embedded IPv4 classified,
 * and a non-global embedded address is refused with the reason its IPv4 form deserves. It would be simpler to
 * refuse all sixteen-byte addresses with one reason and rely on that. It would also mean the embedded-address
 * handling gets written for the first time on the day IPv6 is switched on, which is the worst possible moment
 * for its first test. Doing it now means the eventual v2 relaxes one flag rather than adding a control.
 */
public final class AddressPolicy {
    private AddressPolicy() {}

    /**
     * @param address 4 or 16 bytes, exactly as the resolver returned them
     */
    public static AddressClass classify(byte[] address) {
        if (address == null || (address.length != 4 && address.length != 16)) {
            throw new IllegalArgumentException("An address is four or sixteen bytes.");
        }
        if (address.length == 16) {
            AddressClass special = ipv6SpecialAddress(address);
            if (special != null) {
                return special;
            }
            byte[] embedded = embeddedIpv4(address);
            if (embedded != null) {
                AddressClass embeddedClass = classifyIpv4(embedded);
                // A non-global address does not become acceptable by being written inside an IPv6 one, and the
                // reason reported is the real one. Only a genuinely global embedded address falls through to
                // the blanket v1 refusal below.
                if (!embeddedClass.permitted()) {
                    return embeddedClass;
                }
            }
            return AddressClass.IPV6_NOT_SUPPORTED;
        }
        return classifyIpv4(address);
    }

    /**
     * The two IPv6 addresses that are not addresses of anything, handled before any embedded-IPv4 reading.
     *
     * <p>{@code ::1} and {@code ::} sit inside the range that the deprecated IPv4-compatible form occupies, so
     * reading their low thirty-two bits as IPv4 would classify the IPv6 loopback as {@code 0.0.0.1}. Both are
     * refused either way; naming them means the reason reported is the one a reader would expect.
     */
    private static AddressClass ipv6SpecialAddress(byte[] address) {
        for (int index = 0; index < 15; index++) {
            if (address[index] != 0) {
                return null;
            }
        }
        if (address[15] == 1) {
            return AddressClass.LOOPBACK;
        }
        if (address[15] == 0) {
            return AddressClass.THIS_NETWORK;
        }
        return null;
    }

    /**
     * The IPv4 address embedded in an IPv6 one, or null if there is none.
     *
     * <p>Covers the forms where the low thirty-two bits are an IPv4 address that a dual-stack host may end up
     * sending traffic to: IPv4-mapped, the deprecated IPv4-compatible form, 6to4, and Teredo. For 6to4 and
     * Teredo the embedded address is not the peer the packet ultimately reaches, so treating it as the
     * destination is conservative rather than exact — conservative in the safe direction, since the effect is
     * to refuse.
     */
    private static byte[] embeddedIpv4(byte[] address) {
        boolean firstTenZero = true;
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) {
                firstTenZero = false;
                break;
            }
        }
        if (firstTenZero && (address[10] & 0xff) == 0xff && (address[11] & 0xff) == 0xff) {
            return new byte[] {address[12], address[13], address[14], address[15]};
        }
        if (firstTenZero && address[10] == 0 && address[11] == 0) {
            // ::a.b.c.d, the deprecated IPv4-compatible form. ::1 and :: land here too and classify as
            // loopback and this-network respectively, which is what they are.
            return new byte[] {address[12], address[13], address[14], address[15]};
        }
        if ((address[0] & 0xff) == 0x20 && (address[1] & 0xff) == 0x02) {
            // 2002:V4ADDR::/48 — 6to4 carries its IPv4 in bytes 2..5.
            return new byte[] {address[2], address[3], address[4], address[5]};
        }
        if ((address[0] & 0xff) == 0x20 && (address[1] & 0xff) == 0x01 && address[2] == 0 && address[3] == 0) {
            // 2001:0000:V4ADDR::/48 — Teredo carries the server's IPv4 in bytes 4..7.
            return new byte[] {address[4], address[5], address[6], address[7]};
        }
        return null;
    }

    private static AddressClass classifyIpv4(byte[] address) {
        int first = address[0] & 0xff;
        int second = address[1] & 0xff;
        int third = address[2] & 0xff;

        if (first == 0) {
            return AddressClass.THIS_NETWORK;
        }
        if (first == 10) {
            return AddressClass.PRIVATE_USE;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return AddressClass.SHARED_ADDRESS_SPACE;
        }
        if (first == 127) {
            return AddressClass.LOOPBACK;
        }
        if (first == 169 && second == 254) {
            // Cloud instance metadata at 169.254.169.254 is refused here, as a consequence of the range rather
            // than as a special case. A named test pins that specific address anyway, because the cost of that
            // one being reachable is high enough to deserve a regression test of its own.
            return AddressClass.LINK_LOCAL;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return AddressClass.PRIVATE_USE;
        }
        if (first == 192 && second == 0 && third == 0) {
            return AddressClass.PROTOCOL_ASSIGNMENT;
        }
        if (first == 192 && second == 0 && third == 2) {
            return AddressClass.DOCUMENTATION;
        }
        if (first == 192 && second == 88 && third == 99) {
            return AddressClass.PROTOCOL_ASSIGNMENT;
        }
        if (first == 192 && second == 168) {
            return AddressClass.PRIVATE_USE;
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return AddressClass.BENCHMARKING;
        }
        if (first == 198 && second == 51 && third == 100) {
            return AddressClass.DOCUMENTATION;
        }
        if (first == 203 && second == 0 && third == 113) {
            return AddressClass.DOCUMENTATION;
        }
        if (first >= 224 && first <= 239) {
            return AddressClass.MULTICAST;
        }
        if (first >= 240) {
            // Includes 255.255.255.255, which is refused as reserved rather than named separately.
            return AddressClass.RESERVED;
        }
        return AddressClass.GLOBAL_UNICAST;
    }
}
