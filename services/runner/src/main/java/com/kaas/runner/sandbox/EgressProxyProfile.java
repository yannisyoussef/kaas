package com.kaas.runner.sandbox;

import java.time.Duration;
import java.util.Map;

/**
 * The execution policy the trusted egress proxy runs under.
 *
 * <p>Separate from {@link SandboxSecurityProfile} on purpose, and not a variant of it. The sandbox profile
 * asserts things that are wrong for a proxy — that the container has no network, or exactly one — while a
 * proxy exists to have two. Expressing "the same but with the network rules relaxed" would mean weakening the
 * sandbox profile's own invariants so that a second caller could opt out of them, and those invariants are the
 * reason the sandbox profile is trustworthy.
 *
 * <p>The proxy is <em>trusted</em>, which is a statement about what it is allowed to reach, not a licence to
 * run it unconstrained. Everything that does not conflict with its job still applies: non-root, read-only
 * root filesystem, every capability dropped, no new privileges, no mounts, no daemon socket, and bounded
 * memory, CPU, processes, and logs. A trusted component with a bug is still the component with the most
 * interesting network position in the system.
 *
 * @param version the profile identity recorded on the container, so evidence can name what produced it
 * @param imageReference content-addressed; a tag is refused
 * @param environment everything the proxy needs, supplied entirely by the launcher
 */
public record EgressProxyProfile(
        String version,
        String imageReference,
        String runAsUser,
        long memoryLimitBytes,
        long memorySwapLimitBytes,
        long cpuQuotaMicroseconds,
        long cpuPeriodMicroseconds,
        long pidsLimit,
        long maximumLogBytes,
        Duration readinessTimeout,
        Map<String, String> environment) {

    /** The identity of this policy. Changing any control here changes this string. */
    public static final String VERSION = "kaas.egress-proxy.v1";

    public EgressProxyProfile {
        environment = Map.copyOf(environment);
        if (!SandboxSecurityProfile.NOBODY.equals(runAsUser)) {
            throw new IllegalArgumentException("The egress proxy runs as nobody.");
        }
        if (memoryLimitBytes <= 0 || pidsLimit <= 0 || cpuQuotaMicroseconds <= 0 || maximumLogBytes <= 0) {
            throw new IllegalArgumentException("Every resource ceiling must be set.");
        }
        if (memorySwapLimitBytes != memoryLimitBytes) {
            throw new IllegalArgumentException("Swap must be pinned to the memory limit so it cannot be evaded.");
        }
        if (!SandboxSecurityProfile.isContentAddressedReference(imageReference)) {
            throw new IllegalArgumentException(
                    "The egress proxy image must be pinned by digest, not by a tag: " + imageReference);
        }
        if (readinessTimeout.isNegative() || readinessTimeout.isZero()) {
            throw new IllegalArgumentException("Readiness must be bounded.");
        }
    }

    /** The standard policy, with only the image and the launch environment varying per execution. */
    public static EgressProxyProfile version1(String imageReference, Map<String, String> environment) {
        return new EgressProxyProfile(
                VERSION,
                imageReference,
                SandboxSecurityProfile.NOBODY,
                256L * 1024 * 1024,
                256L * 1024 * 1024,
                50_000,
                100_000,
                256,
                4L * 1024 * 1024,
                Duration.ofSeconds(30),
                environment);
    }
}
