#!/bin/sh
# The handover target for this image.
#
# The source bootstrap ends by exec'ing `/bin/sh /probe.sh <mode>`, which is a compile-time constant and
# deliberately so: nothing it read from the stream chooses what runs next. In the security probe image that
# script is the shell verifier. In this one it is three lines that start the JVM, because this image exists to
# ask what a JVM can do to the filesystem the bootstrap just froze.
#
# It runs AFTER the freeze and AFTER every capability has been dropped, so the JVM it starts inherits exactly
# the posture a future engine would.
exec /opt/java/openjdk/bin/java -XX:MaxRAMPercentage=50 -cp /probe HostileJvmProbe "$@"
