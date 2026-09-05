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
count=0
while [ "$count" -lt 64 ]; do
    read -r line 2>/dev/null || break
    # The blank line separating headers from body, still carrying its CR.
    [ -z "$(printf '%s' "$line" | tr -d '\r')" ] && break
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
