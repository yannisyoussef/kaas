#!/bin/sh
# The bootstrap's handover target in the engine image.
#
# The source bootstrap ends with a fixed `exec /bin/sh /probe.sh <mode>`; that invocation is a compile-time
# constant precisely so nothing it read from the source stream can choose what runs next. In the security
# probe image the script is the shell verifier. Here it is one line that starts the engine.
#
# It runs AFTER the source filesystem is frozen and AFTER every capability has been dropped, so the JVM it
# starts inherits exactly the posture KAAS-20 adjudicated. The mode word is discarded: this image has one
# thing to run.
#
# The crash and dump posture of a JVM that may hold secret values (ADR-034). No heap dump on OOM; exit on OOM
# rather than limp on; no core file on a crash; and the fatal-error log, if one is ever written, lands on the
# sandbox's own bounded, non-executable /tmp, which dies with the container and is never on the host.
exec /opt/java/openjdk/bin/java \
    -XX:MaxRAMPercentage=60 \
    -XX:-HeapDumpOnOutOfMemoryError \
    -XX:+ExitOnOutOfMemoryError \
    -XX:-CreateCoredumpOnCrash \
    -XX:ErrorFile=/tmp/hs_err.log \
    -Djava.io.tmpdir=/tmp \
    -Duser.home=/tmp/kaas-home \
    -cp '/engine/lib/*' \
    com.kaas.karate.KaasKarateAdapter
