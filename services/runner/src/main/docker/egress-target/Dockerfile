# A target for the egress test topology: something the proxy is allowed to reach, and that the sandbox must
# not be able to reach directly.
#
# Pinned by digest for the same reason everything else here is. This image is not part of the production
# system, but it is the thing every no-bypass claim is measured against, and a test fixture that can change
# underneath a security assertion is a security assertion that can quietly stop meaning anything.
FROM busybox@sha256:8d7b1636e974e0adfd8d945955fca609304f0a56c18799dfd032d6e661382d84

COPY responder.sh /responder.sh
COPY hold.sh /hold.sh
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /responder.sh /hold.sh /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
