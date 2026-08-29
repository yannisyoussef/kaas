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

    static SandboxSecurityProfile profile() {
        return SandboxSecurityProfile.version1(probeImage());
    }

    static DockerSandboxLauncher launcher(SandboxSecurityProfile profile, String generation) {
        return new DockerSandboxLauncher(docker(), profile, generation);
    }
}
