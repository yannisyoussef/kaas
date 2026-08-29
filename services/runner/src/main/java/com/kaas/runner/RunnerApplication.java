package com.kaas.runner;

import java.io.PrintStream;

/**
 * The runner's entry point, which still executes nothing.
 *
 * <p>This module now contains a container launcher, which is a meaningful change to what it <em>could</em> do
 * and no change at all to what it does. The launcher runs one repository-controlled security probe under a
 * fixed profile, to produce evidence about the sandbox boundary. It is not an {@code execute(command)} service
 * and deliberately has no general command API: the entire value of the boundary comes from there being exactly
 * one thing it will run.
 */
public final class RunnerApplication {
    static final String BOOTSTRAP_MESSAGE =
            "KaaS runner bootstrap is ready; arbitrary test execution is disabled.";

    /**
     * What this module can do, stated where someone running it will see it. The sandbox exists to be measured,
     * not to be used.
     */
    static final String SECURITY_PROBE_MESSAGE =
            "A hardened sandbox is available for the trusted synthetic security probe only; "
                    + "no feature source, secret, or user-supplied command can enter it.";

    private RunnerApplication() { }

    public static void main(String[] args) {
        run(System.out);
    }

    static void run(PrintStream output) {
        output.println(BOOTSTRAP_MESSAGE);
        output.println(SECURITY_PROBE_MESSAGE);
    }
}
