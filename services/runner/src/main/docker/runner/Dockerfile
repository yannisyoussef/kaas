# The KaaS runner image: the long-lived production runner (KAAS-DEPLOY-001).
#
# It takes work from the control plane's internal claim API and launches sandboxes through the host's Docker
# daemon. It contains no broker client and needs no route to one. The build guard
# verifyLauncherHasNoUserContentDependencies fails if Karate, a secret provider, an object store, the control
# plane, or a RabbitMQ/AMQP client ever reaches this classpath.
#
# What the HOST provides at run time, and this image deliberately does not contain:
#   - the Docker socket                      (mounted; the runner's user needs its group)
#   - runsc, at the path the daemon registers (mounted read-only at that SAME absolute path, so the runner
#                                              measures the binary the daemon will actually execute)
#   - the attestation signing key             (/run/kaas/attestation.key, mounted read-only)
#   - its service credential                  (a client secret file, or a token file an agent refreshes)
# The engine, egress proxy and security probe images it launches are separate release images, referenced by
# digest in its configuration.
#
# Pinned by digest: eclipse-temurin 25.0.4_7-jre-alpine-3.24.
FROM eclipse-temurin@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682

COPY lib/ /app/lib/

# Non-root. Access to the daemon is granted by the operator adding the socket's group (--group-add), which is
# explicit where running as root would be implicit -- though it is stated plainly: a process that can drive the
# Docker daemon can gain root on the host, whichever user it runs as. That is why this image carries nothing
# but the runner.
USER 10001:10001

# The health and metrics port (KAAS_RUNNER_HEALTH_HOST/PORT). Loopback by default; bind it privately.
EXPOSE 9090

ENTRYPOINT ["/opt/java/openjdk/bin/java", "-XX:MaxRAMPercentage=50.0", "-XX:+ExitOnOutOfMemoryError", \
            "-cp", "/app/lib/*", "com.kaas.runner.RunnerApplication"]
