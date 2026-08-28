package com.kaas.runner;

import java.io.PrintStream;

public final class RunnerApplication {
    static final String BOOTSTRAP_MESSAGE =
            "KaaS runner bootstrap is ready; arbitrary test execution is disabled.";

    private RunnerApplication() { }

    public static void main(String[] args) {
        run(System.out);
    }

    static void run(PrintStream output) {
        output.println(BOOTSTRAP_MESSAGE);
    }
}
