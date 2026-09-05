package com.kaas.egress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A deliberately dumb HTTP client that sends exactly the bytes a test names.
 *
 * <p>A real HTTP client library would repair the requests these tests need to send. Half of what is being
 * tested is what happens to a request with two Host headers, a bare LF, or an absolute-form target that
 * disagrees with its Host — and every one of those is something a well-behaved client will not emit and a
 * well-behaved client library will not let through.
 */
final class ProxyClient {

    record Response(int status, String headers, String body) {
        String denialReason() {
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("x-kaas-egress-denial:")) {
                    return line.substring(line.indexOf(':') + 1).strip();
                }
            }
            return null;
        }
    }

    private ProxyClient() {}

    /** Sends raw bytes to the proxy and reads whatever comes back until the connection closes. */
    static Response send(int port, String rawRequest) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(rawRequest.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return read(socket.getInputStream());
        }
    }

    private static Response read(InputStream in) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        try {
            while ((read = in.read(buffer)) >= 0) {
                collected.write(buffer, 0, read);
            }
        } catch (IOException closed) {
            // A reset after the response is written is ordinary; what was collected is the response.
        }
        String text = collected.toString(StandardCharsets.ISO_8859_1);
        if (text.isEmpty()) {
            return new Response(0, "", "");
        }
        int split = text.indexOf("\r\n\r\n");
        String head = split < 0 ? text : text.substring(0, split);
        String body = split < 0 ? "" : text.substring(split + 4);
        String statusLine = head.contains("\r\n") ? head.substring(0, head.indexOf("\r\n")) : head;
        String[] parts = statusLine.split(" ");
        int status = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return new Response(status, head, body);
    }

    static String connect(String authority, String token) {
        return "CONNECT " + authority + " HTTP/1.1\r\n"
                + "Host: " + authority + "\r\n"
                + "Proxy-Authorization: Bearer " + token + "\r\n\r\n";
    }

    static String get(String url, String hostHeader, String token) {
        return "GET " + url + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Proxy-Authorization: Bearer " + token + "\r\n\r\n";
    }
}
