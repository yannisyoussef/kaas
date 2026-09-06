# The trusted synthetic security probe.
#
# Pinned by digest, never by tag: a tag is a mutable pointer, and an image that can change under a
# stable name is a supply-chain hole in the one component whose whole job is to be trustworthy.
# THE BOOTSTRAP IS COMPILED HERE, from this repository's own source, in a pinned stage.
#
# It is the program that populates the sandbox-private source filesystem and then closes it, so it runs
# before anything else in the sandbox and holds the one capability the sandbox is ever granted. Nothing about
# it may be downloaded at run time, pulled from a mutable tag, or built from anything but the file next to
# this one.
#
# Static, because it runs before any of the sandbox's own filesystem is trustworthy and must not depend on a
# dynamic loader, a library path, or anything else that could be influenced.
# gcc:14-bookworm, whose digest was read from two independent sources -- the registry's HTTP API and a
# daemon on a different network -- and matched. The publisher's own tag is not an independent check, because
# it comes from the same place as the image.
FROM gcc@sha256:5e927c284bf55a7dc796262e311a0703344f62f41f5621eb56843111b1d37e15 AS bootstrap
COPY source-bootstrap.c /src/source-bootstrap.c
COPY source-boundary-fixture.c /src/source-boundary-fixture.c
COPY source-setuid-seed.c /src/source-setuid-seed.c
# -Werror, because a warning in the one privileged program in the sandbox is a defect and not a note. The
# link is checked to be genuinely static: a bootstrap that resolved a library at run time would depend on a
# filesystem it is itself responsible for preparing.
RUN gcc -static -O2 -Wall -Wextra -Werror -o /src/source-bootstrap /src/source-bootstrap.c \
    && ! ldd /src/source-bootstrap 2>&1 | grep -q "=>" \
    && echo "the bootstrap built and is statically linked"

# The boundary measurement's fixture planter and the binary it makes setuid. Both are test-only: nothing on
# the delivery path names either, and the launcher reaches the planter only through one probe constant. They
# are built here rather than downloaded because a fixture that arrives at run time is a supply-chain hole in
# the middle of a security measurement.
RUN gcc -static -O2 -Wall -Wextra -Werror -o /src/source-boundary-fixture /src/source-boundary-fixture.c \
    && gcc -static -O2 -Wall -Wextra -Werror -o /src/fixture-setuid /src/source-setuid-seed.c \
    && echo "the boundary fixtures built"

FROM busybox@sha256:8d7b1636e974e0adfd8d945955fca609304f0a56c18799dfd032d6e661382d84

COPY probe.sh /probe.sh

# Owned by root and not writable by the sandbox user, because it is the most privileged program here.
#
# The modes are set by a RUN rather than by COPY --chmod, which needs BuildKit. This image is also built
# through the classic builder by the security suites, and an instruction only one builder understands would
# make the probe image unbuildable exactly where it is needed most.
COPY --from=bootstrap --chown=0:0 /src/source-bootstrap /source-bootstrap

# Test-only, and inert where they sit: /fixture-setuid carries no setuid bit in the image, and the planter is
# only ever the first process when the boundary probe asks for it.
COPY --from=bootstrap --chown=0:0 /src/source-boundary-fixture /source-boundary-fixture
COPY --from=bootstrap --chown=0:0 /src/fixture-setuid /fixture-setuid

RUN chmod 0555 /source-bootstrap /source-boundary-fixture /fixture-setuid

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
