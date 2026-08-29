package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import java.io.File;
import java.nio.file.Path;
import java.util.Set;

/**
 * Builds the trusted probe image from the repository and returns the digest it produced.
 *
 * <p>Building rather than pulling is what makes "allowlisted image" mean something. The image's content is a
 * Dockerfile and a shell script that live in this repository and go through the same review as everything
 * else, and its identity is the digest of what was just built — not a tag, which is a mutable pointer to
 * executable code and therefore a supply-chain hole in the one component whose entire purpose is being
 * trustworthy.
 *
 * <p>The base is pinned by digest in the Dockerfile for the same reason.
 */
public final class ProbeImage {
    private ProbeImage() {}

    /**
     * @param contextDirectory the repository-controlled build context; never a caller-supplied path
     * @return the built image's identity, suitable for use as the profile's image reference
     */
    public static String build(DockerClient docker, Path contextDirectory) {
        File dockerfile = contextDirectory.resolve("Dockerfile").toFile();
        if (!dockerfile.isFile()) {
            throw new IllegalStateException("The probe build context is part of the repository and must exist.");
        }
        String shortId = docker.buildImageCmd()
                .withDockerfile(dockerfile)
                .withBaseDirectory(contextDirectory.toFile())
                // A local tag for readability only. The identity that is trusted is the image id below, which
                // is a digest of the built content.
                .withTags(Set.of("kaas-security-probe:build"))
                .withPull(false)
                .withNoCache(false)
                .exec(new BuildImageResultCallback())
                .awaitImageId();
        // The build reports a twelve-character abbreviation. It is still content-derived, but it is not a
        // digest and it is not unambiguous, so it is resolved to the full identity before anything is run under
        // it. The profile refuses anything less, which is how this was noticed.
        return docker.inspectImageCmd(shortId).exec().getId();
    }
}
