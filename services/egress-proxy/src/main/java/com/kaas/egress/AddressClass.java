package com.kaas.egress;

/**
 * What a resolved address is, as far as egress policy is concerned.
 *
 * <p>Exactly one value permits a connection. Everything else is a named refusal, and the names are chosen to
 * be low-cardinality and stable so they can appear in metrics and cross-tenant logs without carrying the
 * address itself — a resolved address can describe a tenant's internal topology, and an operational log is
 * read by people who are not that tenant.
 */
public enum AddressClass {
    /** Ordinary internet address space. The only class this proxy will connect to. */
    GLOBAL_UNICAST(true),

    /** 127.0.0.0/8 — the proxy's own container, which is trusted infrastructure. */
    LOOPBACK(false),

    /** 10/8, 172.16/12, 192.168/16 — RFC 1918. The networks a proxy compromise would most want to reach. */
    PRIVATE_USE(false),

    /** 169.254.0.0/16 — link-local, which is where cloud instance metadata lives. */
    LINK_LOCAL(false),

    /** 100.64.0.0/10 — carrier-grade NAT space, routable inside a provider and not a tenant destination. */
    SHARED_ADDRESS_SPACE(false),

    /** 0.0.0.0/8 — "this network", including the unspecified address. */
    THIS_NETWORK(false),

    /** 224.0.0.0/4. A destination is one host, not a group. */
    MULTICAST(false),

    /** 240.0.0.0/4, which also contains the limited broadcast address. */
    RESERVED(false),

    /** 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 — documentation ranges. */
    DOCUMENTATION(false),

    /** 198.18.0.0/15 — network benchmarking. */
    BENCHMARKING(false),

    /** 192.0.0.0/24 and 192.88.99.0/24 — IETF protocol assignments and the retired 6to4 relay anycast. */
    PROTOCOL_ASSIGNMENT(false),

    /** Any IPv6 destination. v1 carries IPv4 only; see {@link AddressPolicy} for why that is a refusal. */
    IPV6_NOT_SUPPORTED(false);

    private final boolean permitted;

    AddressClass(boolean permitted) {
        this.permitted = permitted;
    }

    public boolean permitted() {
        return permitted;
    }
}
