package com.kaas.egress;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads one HTTP request from an untrusted socket, strictly.
 *
 * <p>This is a recogniser, not a parser. It accepts one shape and refuses everything else, including shapes
 * that are legal HTTP but ambiguous when a proxy is in the middle. The forms it refuses are the ones that let
 * two participants disagree about where one request ends and the next begins:
 *
 * <ul>
 *   <li><strong>Bare LF line endings.</strong> Accepting them means a header containing a lone LF splits into
 *       two headers for this proxy and stays one for the target, or the reverse.
 *   <li><strong>Obsolete line folding.</strong> A continuation line starting with space or tab is deprecated
 *       and implemented inconsistently; it is a way to hide a header from one reader.
 *   <li><strong>Transfer-Encoding.</strong> Refused outright rather than supported. Nearly every published
 *       request-smuggling technique is a disagreement about chunked framing, and this proxy has no need for
 *       it: it carries one request per connection.
 *   <li><strong>Whitespace around a header name, or a name that is not a token.</strong> {@code Host : x} and
 *       {@code Host: x} are the same header to some readers and different to others.
 * </ul>
 *
 * <p>Sizes are bounded because the client is hostile and the proxy has a memory limit it is expected to stay
 * inside. A request that exceeds a bound is refused rather than truncated: truncation would silently produce a
 * different request from the one that was sent.
 */
public final class RequestReader {

    /** Generous for a request line carrying an absolute URI, far below anything that threatens the heap. */
    static final int MAX_REQUEST_LINE = 8 * 1024;

    static final int MAX_HEADER_LINE = 8 * 1024;

    static final int MAX_HEADERS = 100;

    /** The synthetic workload sends small bodies. A real bound belongs here whatever the workload is. */
    static final int MAX_BODY = 1024 * 1024;

    private RequestReader() {}

    public static ProxyRequest read(InputStream in) throws IOException {
        String requestLine = readLine(in, MAX_REQUEST_LINE);
        if (requestLine.isEmpty()) {
            throw new MalformedRequest("A request begins with a request line.");
        }

        // Exactly three fields separated by exactly one space each. Splitting on runs of whitespace would
        // accept "GET  /x  HTTP/1.1", which some servers read as a target of " /x".
        int firstSpace = requestLine.indexOf(' ');
        int secondSpace = firstSpace < 0 ? -1 : requestLine.indexOf(' ', firstSpace + 1);
        if (firstSpace <= 0 || secondSpace < 0 || requestLine.indexOf(' ', secondSpace + 1) >= 0) {
            throw new MalformedRequest("A request line is a method, a target, and a version.");
        }
        String method = requestLine.substring(0, firstSpace);
        String target = requestLine.substring(firstSpace + 1, secondSpace);
        String version = requestLine.substring(secondSpace + 1);
        if (!isToken(method)) {
            throw new MalformedRequest("A method is a token.");
        }
        if (target.isEmpty()) {
            throw new MalformedRequest("A request line carries a target.");
        }
        if (!"HTTP/1.1".equals(version) && !"HTTP/1.0".equals(version)) {
            throw new MalformedRequest("A request states HTTP/1.1 or HTTP/1.0.");
        }

        List<ProxyRequest.Header> headers = new ArrayList<>();
        while (true) {
            String line = readLine(in, MAX_HEADER_LINE);
            if (line.isEmpty()) {
                break;
            }
            if (headers.size() >= MAX_HEADERS) {
                throw new MalformedRequest("A request carries at most " + MAX_HEADERS + " headers.");
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new MalformedRequest("A request uses no obsolete line folding.");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new MalformedRequest("A header is a name, a colon, and a value.");
            }
            String name = line.substring(0, colon);
            if (!isToken(name)) {
                // Catches a space before the colon, which is the whole of the "Host :" trick.
                throw new MalformedRequest("A header name is a token.");
            }
            // Optional whitespace after the colon is legal and unambiguous; leading and trailing spaces and
            // tabs in the value are stripped, which every implementation agrees on.
            String value = line.substring(colon + 1).strip();
            headers.add(new ProxyRequest.Header(name, value));
        }

        ProxyRequest withoutBody = new ProxyRequest(method, target, version, headers, new byte[0]);
        if (withoutBody.singleHeader("transfer-encoding") != null) {
            throw new MalformedRequest("A request uses Content-Length framing, not Transfer-Encoding.");
        }
        String contentLength = withoutBody.singleHeader("content-length");
        if (contentLength == null) {
            return withoutBody;
        }
        int length = parseContentLength(contentLength);
        byte[] body = in.readNBytes(length);
        if (body.length != length) {
            throw new MalformedRequest("A request body is as long as its Content-Length says.");
        }
        return new ProxyRequest(method, target, version, headers, body);
    }

    private static int parseContentLength(String value) {
        if (value.isEmpty() || value.length() > 7) {
            throw new MalformedRequest("A Content-Length is a decimal length within the accepted bound.");
        }
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                // Refuses "+5", "5, 5" — the duplicate-header value that a comma-joining reader produces — and
                // anything else Integer.parseInt would have its own opinion about.
                throw new MalformedRequest("A Content-Length is decimal digits.");
            }
        }
        int length = Integer.parseInt(value);
        if (length > MAX_BODY) {
            throw new MalformedRequest("A request body is at most " + MAX_BODY + " bytes.");
        }
        return length;
    }

    /**
     * Reads one CRLF-terminated line and returns it without the terminator.
     *
     * <p>A CR not followed by LF, or an LF not preceded by CR, is a refusal rather than a line break. Reading
     * byte at a time is deliberate: a buffered reader would consume bytes past the end of the headers, which
     * for a CONNECT request would swallow the beginning of the tunnel.
     */
    private static String readLine(InputStream in, int limit) throws IOException {
        byte[] buffer = new byte[limit];
        int length = 0;
        while (true) {
            int next = in.read();
            if (next < 0) {
                throw new MalformedRequest("A request ended mid-line.");
            }
            if (next == '\r') {
                int after = in.read();
                if (after != '\n') {
                    throw new MalformedRequest("A line ends with CRLF.");
                }
                return new String(buffer, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (next == '\n') {
                throw new MalformedRequest("A line ends with CRLF, not a bare LF.");
            }
            if (next == 0) {
                throw new MalformedRequest("A request carries no NUL bytes.");
            }
            if (length == limit) {
                throw new MalformedRequest("A request line is at most " + limit + " bytes.");
            }
            buffer[length++] = (byte) next;
        }
    }

    /** RFC 9110 token characters. Written out rather than approximated by a character-class shorthand. */
    private static boolean isToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(character) >= 0;
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
