package com.kaas.runner;

import com.kaas.runner.daemon.RunnerComposition;
import com.kaas.runner.daemon.RunnerConfiguration;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;

/**
 * The runner's entry point: the long-lived production runner (KAAS-DEPLOY-001).
 *
 * <p>Until this slice this printed a banner and exited: the execution loop, the launcher and the gates all
 * existed, and nothing a deployment could start composed them. {@code main} now validates configuration, builds
 * THE production composition ({@link RunnerComposition}) and runs it until SIGTERM, when it shuts down in the
 * documented order and exits.
 *
 * <p>There is still no general command API. The runner executes exactly one kind of thing -- an assignment the
 * control plane handed it, through the execution loop, under the security profile -- and takes no command,
 * image or argument list from anyone.
 *
 * <pre>
 *   exit 0  stopped after SIGTERM
 *   exit 2  configuration invalid; every problem is printed by variable name, never by value
 *   exit 1  could not start (the health port could not be bound, say)
 * </pre>
 */
public final class RunnerApplication {
    private RunnerApplication() {}

    public static void main(String[] args) {
        System.exit(run(System.getenv(), System.out));
    }

    static int run(Map<String, String> environment, PrintStream output) {
        RunnerConfiguration configuration;
        try {
            configuration = RunnerConfiguration.fromEnvironment(environment);
        } catch (RunnerConfiguration.Invalid invalid) {
            output.println("runner=CONFIGURATION_INVALID");
            invalid.problems().forEach(problem -> output.println("  " + problem));
            return 2;
        }
        RunnerComposition.Runner runner;
        try {
            runner = RunnerComposition.compose(configuration);
        } catch (java.io.IOException | RuntimeException cannotStart) {
            output.println("runner=START_FAILED error=" + cannotStart.getClass().getSimpleName());
            return 1;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(runner::close, "kaas-runner-shutdown"));
        runner.start();
        output.println("runner=STARTED " + configuration + " health=" + configuration.healthHost() + ":"
                + runner.healthPort());
        try {
            while (!runner.daemon().awaitStopped(Duration.ofDays(1))) {
                // Runs until SIGTERM; the shutdown hook stops the daemon and this returns.
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        output.println("runner=STOPPED");
        return 0;
    }
}
