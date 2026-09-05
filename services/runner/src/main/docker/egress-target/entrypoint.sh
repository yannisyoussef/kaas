#!/bin/sh
# Two listeners: HTTP on 80, and a socket on 8443 that accepts and holds.
#
# Both are persistent (-lk), so one exchange does not end the target. A target that served exactly one
# request would make every test after the first in a topology report an unreachable destination, which looks
# identical to the isolation working.
set -u
nc -lk -p 8443 -e /hold.sh &
exec nc -lk -p 80 -e /responder.sh
