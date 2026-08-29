package com.kaas.runner.gate;

/**
 * One control, its verdict, and whether the platform is allowed to proceed without it.
 *
 * <p>The classification is the honest part. A control that a particular host cannot enforce is reported as
 * unsupported rather than passed, and a mandatory control that cannot be demonstrated fails the gate outright.
 * The alternative — logging a warning and continuing — produces a green assessment that means nothing, which
 * is worse than no assessment at all.
 */
public record SecurityCheck(String control, Verdict verdict, Enforcement enforcement, String evidence) {

    public enum Verdict {
        /** Demonstrated from inside the sandbox. */
        PASS,
        /** Demonstrated to be absent or ineffective. */
        FAIL,
        /** The runtime on this host cannot enforce or cannot report it. Never counted as a pass. */
        UNSUPPORTED
    }

    public enum Enforcement {
        /**
         * Portable across every host KaaS runs on and provable from inside the sandbox. The gate fails if any
         * of these is not PASS.
         */
        MANDATORY,
        /**
         * Real hardening that varies by host — a custom seccomp profile, AppArmor, SELinux, user namespaces, a
         * rootless daemon. Reported for operational visibility, never required, and never claimed as enforced
         * where it cannot be shown.
         */
        DEPLOYMENT_SPECIFIC
    }

    public boolean blocksRelease() {
        return enforcement == Enforcement.MANDATORY && verdict != Verdict.PASS;
    }
}
