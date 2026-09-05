package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import java.nio.file.Path;

/**
 * The package-private test wiring, opened to the gate's own test package.
 *
 * <p>{@code SandboxTestSupport} is package-private on purpose — it builds images and holds a daemon
 * connection, and nothing outside the sandbox tests has a reason to reach it. This is the narrowest opening
 * that lets the gate suite reuse the same client and the same images rather than building second copies, which
 * would mean two images could differ and the evidence would describe one of them without saying which.
 */
public final class SandboxTestAccess {

    private SandboxTestAccess() {}

    public static DockerClient docker() {
        return SandboxTestSupport.docker();
    }

    public static String probeImage() {
        return SandboxTestSupport.probeImage();
    }

    public static Path proxyImageContext() {
        String context = System.getProperty("kaas.egress.proxy.context");
        if (context == null) {
            throw new IllegalStateException(
                    "kaas.egress.proxy.context is set by the build from the proxy image context dependency.");
        }
        return Path.of(context);
    }
}
