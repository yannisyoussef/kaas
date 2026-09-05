package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Shared wiring for the hostile-execution tests: one daemon connection, one built probe image.
 *
 * <p>The image is built once for the whole suite because building it per test would dominate the runtime, and
 * the thing under test is the sandbox policy rather than the build.
 */
final class SandboxTestSupport {
    private static DockerClient client;
    private static String imageReference;

    private SandboxTestSupport() {}

    static synchronized DockerClient docker() {
        if (client == null) {
            var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            var http = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .sslConfig(config.getSSLConfig())
                    .responseTimeout(Duration.ofSeconds(60))
                    .connectionTimeout(Duration.ofSeconds(30))
                    .build();
            client = DockerClientImpl.getInstance(config, http);
        }
        return client;
    }

    static synchronized String probeImage() {
        if (imageReference == null) {
            imageReference = ProbeImage.build(docker(), probeContext());
        }
        return imageReference;
    }

    /** The repository-controlled build context, located from the module rather than from configuration. */
    static Path probeContext() {
        Path fromModule = Path.of("src", "main", "docker", "probe");
        return fromModule.toFile().isDirectory()
                ? fromModule
                : Path.of("services", "runner", "src", "main", "docker", "probe");
    }

    private static String proxyImageReference;

    private static String targetImageReference;

    /**
     * The egress proxy image, built once from the context Gradle assembled.
     *
     * <p>The path comes from a system property the build sets from a resolved dependency, not from a guess
     * about where another module's build directory is. A guess would silently build whatever a previous build
     * left there, which is an image nobody compiled in this run.
     */
    static synchronized String egressProxyImage() {
        if (proxyImageReference == null) {
            String context = System.getProperty("kaas.egress.proxy.context");
            if (context == null) {
                throw new IllegalStateException(
                        "kaas.egress.proxy.context is set by the build from the proxy image context "
                                + "dependency; without it these tests would silently build nothing.");
            }
            proxyImageReference = EgressProxyImage.build(docker(), Path.of(context));
        }
        return proxyImageReference;
    }

    /** The egress test target image, from this module's own repository-controlled context. */
    static synchronized String egressTargetImage() {
        if (targetImageReference == null) {
            targetImageReference = ProbeImage.build(docker(), egressTargetContext());
        }
        return targetImageReference;
    }

    static Path egressTargetContext() {
        Path fromModule = Path.of("src", "main", "docker", "egress-target");
        return fromModule.toFile().isDirectory()
                ? fromModule
                : Path.of("services", "runner", "src", "main", "docker", "egress-target");
    }

    static SandboxSecurityProfile profile() {
        return SandboxSecurityProfile.version1(probeImage());
    }

    static DockerSandboxLauncher launcher(SandboxSecurityProfile profile, String generation) {
        return new DockerSandboxLauncher(docker(), profile, generation);
    }
}
