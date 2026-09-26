#!/bin/sh
# One HTTP exchange for the egress test topology. Invoked by "nc -lk -e", so stdin and stdout are the
# accepted socket.
#
# Written as a shell responder rather than served by busybox httpd because this busybox is built without CGI,
# so a redirect could not be produced at all — httpd returned the script's source with a 200. Discovered by
# running it rather than by reading about it. A responder that emits the bytes it is asked to emit is also
# easier to reason about than a web server's opinions about what a request meant.
set -u

read -r method target version 2>/dev/null || exit 0

# Drain the remaining headers. Responding before the request has been read can make the peer see a reset
# instead of the response when the socket closes, which would look like an unreachable target.
#
# The Authorization header is kept, in this process only, for the /auth and /echo-auth paths. It is never
# written to this container's output: the target records a VERDICT, not a credential.
count=0
authorization=""
while [ "$count" -lt 64 ]; do
    read -r line 2>/dev/null || break
    line=$(printf '%s' "$line" | tr -d '\r')
    # The blank line separating headers from body.
    [ -z "$line" ] && break
    case "$line" in
    [Aa]uthorization:*) authorization=${line#*: } ;;
    esac
    count=$((count + 1))
done

send() {
    printf '%s\r\n' "$1"
    printf 'Content-Length: %s\r\n' "${#2}"
    printf 'Connection: close\r\n'
    printf '\r\n'
    printf '%s' "$2"
}

case "$target" in
*/ok*)
    # The sentinel the probe looks for. Its presence is what distinguishes "the proxy carried the request"
    # from "something answered".
    send 'HTTP/1.1 200 OK' 'KAAS_EGRESS_TARGET_OK'
    ;;
*/auth)
    # A CONTROLLED TARGET for secret-bearing execution. It accepts exactly one bearer token: the one whose
    # SHA-256 the test configured in KAAS_EXPECTED_AUTH_SHA256. The token itself is never configured here and
    # never logged; only its digest is known to this container, and only the verdict is written to stderr,
    # which is this container's log. An absent or empty expectation accepts nothing.
    token=${authorization#Bearer }
    presented=$(printf '%s' "$token" | sha256sum | cut -d' ' -f1)
    if [ -n "${KAAS_EXPECTED_AUTH_SHA256:-}" ] && [ "$presented" = "$KAAS_EXPECTED_AUTH_SHA256" ]; then
        echo "auth_result=AUTHENTICATED" >&2
        send 'HTTP/1.1 200 OK' 'KAAS_SECRET_AUTHENTICATED'
    else
        echo "auth_result=REJECTED" >&2
        send 'HTTP/1.1 401 Unauthorized' 'KAAS_SECRET_REJECTED'
    fi
    ;;
*/echo-auth)
    # A hostile-shaped target: it refuses and reflects the credential it was sent in the failure body, which is
    # what a real API error page sometimes does. The engine's failure output then carries the secret, and the
    # platform must not keep it.
    send 'HTTP/1.1 500 Internal Server Error' "rejected credential: ${authorization}"
    ;;
*/redirect*)
    # Escape by redirect. The proxy does not follow this; the client may, and that second request is a new
    # proxied request which has to be authorized on its own. Where it points is set by the launcher.
    printf 'HTTP/1.1 302 Found\r\n'
    printf 'Location: %s\r\n' "${KAAS_REDIRECT_TARGET:-http://denied.example.com:80/ok}"
    printf 'Content-Length: 0\r\n'
    printf 'Connection: close\r\n'
    printf '\r\n'
    ;;
*)
    send 'HTTP/1.1 404 Not Found' 'KAAS_EGRESS_TARGET_NO_SUCH_PATH'
    ;;
esac
