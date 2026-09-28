package com.kaas.runner.daemon;

import com.github.dockerjava.api.DockerClient;
import com.kaas.runner.attestation.RuntimeImplementation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What must be true of this host before a new assignment is safe, checked against the daemon itself.
 *
 * <p>The configured runtime, and only it. A runner configured for gVisor on a host where {@code runsc} is not
 * registered is NOT READY -- it does not start sandboxes under {@code runc} instead, and there is no code path
 * here or in the launcher that would. A fallback from the stronger boundary to the weaker one is the single
 * change this check exists to make impossible.
 *
 * <p>Images are checked for presence, not pulled. They arrive with the release, placed by the host deployment,
 * and an execution host that could fetch images on demand would need a registry route and credential it
 * otherwise has no reason to hold.
 */
final class RuntimePreflight {
    private final DockerClient docker;
    private final RunnerConfiguration configuration;
    private final Readiness readiness;

    RuntimePreflight(DockerClient docker, RunnerConfiguration configuration, Readiness readiness) {
        this.docker = docker;
        this.configuration = configuration;
        this.readiness = readiness;
    }

    /**
     * Runs every check and records each condition.
     *
     * @return the runtime implementation digest measured now, when the runtime check passed
     */
    Optional<String> check() {
        try {
            docker.pingCmd().exec();
            readiness.holds(Readiness.Condition.DOCKER);
        } catch (RuntimeException unreachable) {
            readiness.set(Readiness.Condition.DOCKER, false, "DOCKER_UNREACHABLE");
            readiness.set(Readiness.Condition.RUNTIME, false, "DOCKER_UNREACHABLE");
            readiness.set(Readiness.Condition.IMAGES, false, "DOCKER_UNREACHABLE");
            return Optional.empty();
        }
        Optional<String> digest = runtime();
        images();
        return digest;
    }

    private Optional<String> runtime() {
        String name = configuration.sandboxRuntime().daemonRuntimeName();
        Path registered;
        try {
            // The daemon's own registration for the CONFIGURED runtime name. Absent is NOT READY; there is no
            // second name tried.
            registered = RuntimeImplementation.registeredPath(docker, name);
        } catch (RuntimeException notRegistered) {
            readiness.set(Readiness.Condition.RUNTIME, false, "RUNTIME_NOT_REGISTERED");
            return Optional.empty();
        }
        if (configuration.runscPath().isPresent() && !registered.equals(configuration.runscPath().orElseThrow())) {
            // The daemon would run a different binary than the one Operations installed and configured.
            readiness.set(Readiness.Condition.RUNTIME, false, "RUNTIME_PATH_MISMATCH");
            return Optional.empty();
        }
        try {
            RuntimeImplementation implementation = RuntimeImplementation.measure(docker, name);
            readiness.holds(Readiness.Condition.RUNTIME);
            return Optional.of(implementation.digest());
        } catch (RuntimeException unmeasurable) {
            // Registered but not measurable from here: typically the runner's container does not see the host's
            // runtime binary at the registered path. Evidence could not describe it, so nothing may run under it.
            readiness.set(Readiness.Condition.RUNTIME, false, "RUNTIME_UNMEASURABLE");
            return Optional.empty();
        }
    }

    private void images() {
        List<String> required = new ArrayList<>(List.of(configuration.probeImage(), configuration.engineImage()));
        configuration.egress().ifPresent(egress -> required.add(egress.proxyImage()));
        for (String image : required) {
            try {
                docker.inspectImageCmd(image).exec();
            } catch (RuntimeException absent) {
                readiness.set(Readiness.Condition.IMAGES, false, "IMAGE_ABSENT");
                return;
            }
        }
        readiness.holds(Readiness.Condition.IMAGES);
    }
}
