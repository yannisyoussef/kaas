# The KaaS API image: the control plane, and the migrator that belongs to it (KAAS-DEPLOY-001).
#
# ONE image, three entrypoints, so the migrator and the application can never be built from different sources:
#
#   kaas-api serve          the control plane (default)
#   kaas-api migrate        Flyway only; no web server, no scheduler, no consumer; exits 0/1/2
#   kaas-api deploy-check   the post-deploy synthetic check; exits 0 only when the platform base can take work
#
# Pinned by digest, never by tag: eclipse-temurin 25.0.4_7-jre-alpine-3.24, the same multi-architecture index
# the egress proxy image uses. The source revision is stamped on by the release build as the OCI label
# org.opencontainers.image.revision; an image without it is refused by the release manifest verifier.
FROM eclipse-temurin@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682

# The jar this build compiled and exactly its runtime classpath, assembled by Gradle. Nothing is downloaded.
COPY lib/ /app/lib/
COPY kaas-api /app/bin/kaas-api
RUN chmod 0555 /app/bin/kaas-api

# Non-root. The application needs no privilege: it listens on unprivileged ports and writes nothing to disk.
USER 10001:10001

# 8080 the API; 8081 management (health, deployment status, metrics) -- bind 8081 privately, never publicly.
EXPOSE 8080 8081

ENTRYPOINT ["/app/bin/kaas-api"]
CMD ["serve"]
