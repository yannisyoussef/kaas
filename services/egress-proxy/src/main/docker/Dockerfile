# The trusted KaaS egress proxy.
#
# Pinned by digest, never by tag. A tag is a mutable pointer to executable code, and this is the one component
# that sits on both an untrusted network and a network the untrusted side cannot otherwise reach — which makes
# an unpinned base image the shortest path from a registry compromise to a pivot into the target network.
#
# sha256:3137541d… is eclipse-temurin 25.0.4_7-jre-alpine-3.24, a multi-architecture OCI index, so the same
# digest resolves on the amd64 CI runner and on an arm64 development machine. Pinning a platform-specific
# manifest instead would build on one and fail on the other, and the usual repair for that is to un-pin.
FROM eclipse-temurin@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682

# Assembled by Gradle from the jar it just built plus exactly that jar's runtime classpath. Copying a
# hand-maintained list would mean the image could run code that this build never compiled or tested.
COPY lib/ /app/lib/

# Non-root in the image as well as in the launcher's configuration. Neither alone is enough: a launcher can
# override the image's USER, and a launcher-only setting leaves an image that runs as root anywhere else.
USER 65534:65534

# No caller-supplied string ever becomes a command argument. Everything the proxy needs arrives as an
# environment variable, and every one of them is required — the process refuses to start without any of them
# rather than falling back to a default that nobody chose deliberately.
#
# -XX:-UsePerfData stops the JVM writing hsperfdata into the temporary directory at startup, which is what
# lets the container run with a read-only root filesystem and no writable mount at all. Without it the JVM
# needs somewhere to write before any of this code runs.
ENTRYPOINT ["java", \
    "-XX:-UsePerfData", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.awt.headless=true", \
    "-cp", "/app/lib/*", \
    "com.kaas.egress.ProxyMain"]
