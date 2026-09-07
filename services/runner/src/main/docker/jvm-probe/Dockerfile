# The hostile JVM probe.
#
# A separate image from the security probe on purpose. That one is busybox and is what every existing gate
# measures; adding a JVM to it would change the thing under test in all of them for the sake of one
# measurement. This exists only to answer whether a JVM workload can be contained, which is the question that
# matters once the engine that would run tenant code is a JVM.
#
# Two stages, both digest-pinned from two independent sources. The compile stage carries a JDK; the stage the
# sandbox actually runs carries only a JRE, so there is no compiler inside the sandbox. Nothing is downloaded
# at build time -- an earlier version reached for `apk add` when javac was missing, which is a package manager
# running during a security measurement's build and is exactly the shape a pinned supply chain exists to
# avoid.
FROM eclipse-temurin@sha256:09349d79941fd53bb3d487b393ca118d8853c08c09193f416fe6a8718df9e732 AS compile
COPY HostileJvmProbe.java /src/HostileJvmProbe.java
RUN javac -d /probe /src/HostileJvmProbe.java

FROM eclipse-temurin@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682

COPY --from=compile /probe /probe

# Non-root in the image as well as in the launcher, for the same reason as the security probe: the image's
# USER is advisory because a launcher can override it, and a launcher-only setting leaves an image that runs
# as root anywhere else it is used.
USER 65534:65534

# A fixed entrypoint, named by ABSOLUTE PATH.
#
# Not a style choice. Launched through the platform's own launcher, a bare `java` is not resolvable: the
# daemon refuses the start with `exec: "java": executable file not found in $PATH`. The launcher sets the
# sandbox's environment explicitly rather than inheriting one, and whatever the interaction is between that
# and the image's own ENV, the measured outcome is that the lookup fails. The security probe has never hit
# this because its entrypoint is /bin/sh, which is absolute.
#
# So: every image this launcher runs names its program by absolute path. The launcher supplies only a mode
# selected from a server-side enumeration, so no caller-supplied string ever becomes part of this command line.
ENTRYPOINT ["/opt/java/openjdk/bin/java", "-XX:MaxRAMPercentage=50", "-cp", "/probe", "HostileJvmProbe"]
