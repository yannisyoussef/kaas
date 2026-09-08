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
exec /opt/java/openjdk/bin/java \
    -XX:MaxRAMPercentage=60 \
    -Djava.io.tmpdir=/tmp \
    -Duser.home=/tmp/kaas-home \
    -cp '/engine/lib/*' \
    com.kaas.karate.KaasKarateAdapter
