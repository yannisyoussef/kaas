package com.kaas.egress;

import java.net.InetAddress;
import java.util.List;

/**
 * The addresses a destination resolved to, all of them already classified as permitted.
 *
 * <p>Holding {@link InetAddress} objects built from raw bytes is the point of this type. What gets connected
 * to is one of these objects, and an {@code InetAddress} constructed from bytes never performs a lookup — so
 * there is no second resolution between the classification and the connection, because there is no name left
 * to resolve.
 */
public record ResolvedTarget(CanonicalDestination destination, List<InetAddress> addresses) {}
