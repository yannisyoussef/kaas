package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import java.io.File;
import java.nio.file.Path;
import java.util.Set;

/**
 * Builds the trusted egress proxy image from the repository and returns the digest it produced.
 *
 * <p>Built rather than pulled, for the same reason the probe image is: the proxy's content is source in this
 * repository that went through the same review as everything else, and its identity is the digest of what was
 * just built. A tag would be a mutable pointer to executable code in the one component that sits on both an
 * untrusted network and a network the untrusted side cannot otherwise reach.
 *
 * <p>The build context is assembled by Gradle — the Dockerfile from source control plus exactly the jars on
 * the proxy's own runtime classpath — and its location is supplied by the build, never by a caller. There is
 * no parameter here that would let anything choose a different image.
 */
public final class EgressProxyImage {

    private EgressProxyImage() {}

    /**
     * @param contextDirectory the Gradle-assembled, repository-controlled build context
     * @return the built image's full content-addressed identity
     */
    public static String build(DockerClient docker, Path contextDirectory) {
        File dockerfile = contextDirectory.resolve("Dockerfile").toFile();
        if (!dockerfile.isFile()) {
            throw new IllegalStateException(
                    "The egress proxy build context is produced by the build and must exist: " + contextDirectory);
        }
        if (!contextDirectory.resolve("lib").toFile().isDirectory()) {
            // The Dockerfile alone would build an image with no proxy in it, which would start, fail to find
            // its main class, and be indistinguishable from a proxy that crashed. Better to refuse here.
            throw new IllegalStateException("The egress proxy build context carries the proxy's own jars.");
        }
        String shortId = docker.buildImageCmd()
                .withDockerfile(dockerfile)
                .withBaseDirectory(contextDirectory.toFile())
                // A local tag for human readability only. What is trusted is the identity returned below.
                .withTags(Set.of("kaas-egress-proxy:build"))
                .withPull(false)
                .withNoCache(false)
                .exec(new BuildImageResultCallback())
                .awaitImageId();
        // The build reports a twelve-character abbreviation. Content-derived, but not a digest and not
        // unambiguous — the kaas-10 lesson. Resolved to the full identity before anything runs under it.
        return docker.inspectImageCmd(shortId).exec().getId();
    }
}
