# The trusted synthetic security probe.
#
# Pinned by digest, never by tag: a tag is a mutable pointer, and an image that can change under a
# stable name is a supply-chain hole in the one component whose whole job is to be trustworthy.
FROM busybox@sha256:8d7b1636e974e0adfd8d945955fca609304f0a56c18799dfd032d6e661382d84

COPY probe.sh /probe.sh

# A directory the sandbox user owns.
#
# Without it the read-only-root check proves nothing: the probe would try to write to "/", which user 65534
# cannot write to whether the filesystem is read-only or not, so the test passes on file permissions and would
# keep passing with read-only disabled. Writing somewhere the user genuinely owns makes the mount flag the only
# thing that can refuse.
RUN mkdir -p /probe-owned && chown 65534:65534 /probe-owned

# Non-root in the image as well as in the launcher configuration. Neither alone is sufficient: the
# image's USER is advisory because a launcher can override it, and a launcher-only setting leaves an
# image that runs as root anywhere else it is used.
USER 65534:65534

# A fixed entrypoint. The launcher supplies only a mode selected from a server-side enumeration, so
# no caller-supplied string ever becomes shell syntax.
ENTRYPOINT ["/bin/sh", "/probe.sh"]
CMD ["inspect"]
