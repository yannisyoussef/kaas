#!/bin/sh
# Accepts a connection and holds it open without saying anything.
#
# This is the far end of the long-lived tunnel test. It must not close first: the measurement is how long the
# tunnel stays usable after the assignment is fenced, and a target that hung up on its own would end the
# tunnel for a reason that has nothing to do with fencing and would read as a successful revocation.
sleep "${KAAS_HOLD_SECONDS:-300}"
