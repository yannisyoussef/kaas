# The hostile JVM probe.
#
# A separate image from the security probe on purpose. That one is busybox and is what every existing gate
# measures; adding a JVM to it would change the thing under test in every one of those gates for the sake of
# one measurement. This image exists only to answer whether a JVM workload can be contained, which is the
# question that matters once the future engine is a JVM.
#
# Pinned by digest from two independent sources, like every other image here.
FROM eclipse-temurin@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682

COPY HostileJvmProbe.java /HostileJvmProbe.java

# Compiled at build time so the sandbox runs a class file rather than a compiler. A JDK in the sandbox would
# be a compiler in the sandbox, which is exactly the sort of tool a minimal engine image must not carry.
USER 0:0
RUN mkdir -p /probe && \
    (javac -d /probe /HostileJvmProbe.java 2>/dev/null \
      || (apk add --no-cache openjdk25-jdk >/dev/null 2>&1 && javac -d /probe /HostileJvmProbe.java)) && \
    rm -f /HostileJvmProbe.java && \
    apk del openjdk25-jdk >/dev/null 2>&1 || true

# Non-root in the image as well as in the launcher, for the same reason as the security probe: neither alone
# is sufficient.
USER 65534:65534

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-cp", "/probe", "HostileJvmProbe"]
