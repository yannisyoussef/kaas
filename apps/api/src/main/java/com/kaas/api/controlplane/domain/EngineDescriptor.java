package com.kaas.api.controlplane.domain;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which engine will execute a run, and at which version.
 *
 * <p><strong>This must name the engine that actually runs.</strong> Until this slice it was hardcoded to
 * {@code KARATE}, which was false in a way that mattered: no Karate exists anywhere in this repository, so
 * every command declared an engine that could not have run it. Once a runner began executing commands, that
 * would have meant synthetic shell assertions being reported as a Karate suite — to every dashboard, every
 * result consumer, and every operator reading a report. A platform that misreports what it ran is worse than
 * one that runs nothing.
 *
 * <p>{@code SYNTHETIC} is the platform's own workload: a fixed, deterministic set of assertions the platform
 * wrote, used to prove the execution lifecycle composes before any user-controlled content is admitted. It is
 * the honest default while no engine is integrated.
 *
 * <p>{@code KARATE} remains a valid value so the model can be built toward it, and the runner refuses to
 * execute it — it has no Karate to run. Keeping the value while refusing it at execution is the same shape as
 * the network policy: represented in the model, unenforceable in this deployment, and refused rather than
 * silently degraded.
 */
public record EngineDescriptor(String engine, String version) {

    /** The platform's own synthetic workload. Not a test engine, and never reported as one. */
    public static final String SYNTHETIC = "SYNTHETIC";

    /** Modelled, not integrated. No runner in this repository can execute it. */
    public static final String KARATE = "KARATE";

    private static final Set<String> KNOWN = Set.of(SYNTHETIC, KARATE);

    private static final Pattern VERSION =
            Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?");

    public EngineDescriptor {
        if (engine == null || !KNOWN.contains(engine) || version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("The configured engine descriptor is invalid.");
        }
    }
}
