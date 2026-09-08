# The Karate engine image.
#
# THE CLASSPATH IS THE SECURITY CONTROL. Karate resolves arbitrary classes by name with no allowlist, so
# whatever is in this image is reachable from tenant source and whatever is absent is not. That is why this
# image is built from one module's runtime classpath and nothing else: no runner code, no control-plane
# client, no Docker client, no database driver, no broker client, no cloud SDK, no secret provider.
#
# Two stages, both pinned by digest read from two independent sources. The compile stage carries a JDK; the
# stage the sandbox runs carries a JRE, so there is no compiler inside the sandbox to hand hostile code.
FROM gcc@sha256:5e927c284bf55a7dc796262e311a0703344f62f41f5621eb56843111b1d37e15 AS bootstrap
COPY source-bootstrap.c /src/source-bootstrap.c
RUN gcc -static -O2 -Wall -Wextra -Werror -o /src/source-bootstrap /src/source-bootstrap.c \
    && ! ldd /src/source-bootstrap 2>&1 | grep -q "=>"

FROM eclipse-temurin@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682

# The engine: the adapter jar and exactly the runtime dependencies Gradle resolved for it. Assembled by the
# build rather than fetched here -- nothing is downloaded at image build time and nothing at run time.
COPY lib /engine/lib

# The source bootstrap, unchanged, from the same C file the security probe uses. It freezes the source
# filesystem and drops every capability before anything on the classpath above can run.
COPY --from=bootstrap /src/source-bootstrap /source-bootstrap

# The bootstrap's handover target. Its final act is a fixed `exec /bin/sh /probe.sh <mode>`, so in this image
# that script is what starts the engine.
COPY probe.sh /probe.sh

RUN chmod 0555 /source-bootstrap /probe.sh

# Non-root in the image as well as in the launcher. Neither alone is sufficient: the image's USER is advisory
# because a launcher can override it, and a launcher-only setting leaves an image that runs as root elsewhere.
USER 65534:65534

# Absolute path, because the launcher builds the sandbox's environment explicitly and a bare `java` is not
# resolvable there -- measured in KAAS-20 as `exec: "java": executable file not found in $PATH`.
#
# The classpath is a fixed platform string. Tenant source cannot extend it, reorder it, or name a jar: there
# is no argument here that anything outside this repository supplies.
ENTRYPOINT ["/opt/java/openjdk/bin/java", "-XX:MaxRAMPercentage=60", \
            "-Djava.io.tmpdir=/tmp", "-Duser.home=/tmp/kaas-home", \
            "-cp", "/engine/lib/*", "com.kaas.karate.KaasKarateAdapter"]
