package com.kaas.runner.sandbox;

import java.util.Objects;
import java.util.UUID;

/**
 * Everything a caller may say about a sandbox.
 *
 * <p>Three fields, none of which is a container setting. That is the point: the launcher derives the image,
 * the user, the capabilities, the network, the mounts, the limits and the environment itself, so there is no
 * argument a caller could pass that would weaken the policy. Making a dangerous configuration
 * <em>unrepresentable</em> is stronger than validating it away, because validation is a thing you can forget
 * to do and a type is not.
 */
public record SandboxLaunchRequest(SyntheticProbe probe, String profileVersion, UUID correlationId) {

    public SandboxLaunchRequest {
        Objects.requireNonNull(probe, "A sandbox runs one of the enumerated synthetic probes.");
        Objects.requireNonNull(profileVersion, "A sandbox is launched under a named security profile version.");
        Objects.requireNonNull(correlationId, "A sandbox is correlated so its evidence can be traced.");
    }
}
