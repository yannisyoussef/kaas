package com.kaas.egress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The only path out of an execution sandbox.
 *
 * <h2>What this is, and what it deliberately is not</h2>
 *
 * <p>It speaks exactly two things: an ordinary forward-proxied HTTP request, and a CONNECT tunnel. There is no
 * SOCKS, no UDP association, no arbitrary port forwarding, and no protocol relay. Each of those would be a
 * general-purpose channel through a component that sits on both an untrusted network and a network the
 * untrusted side cannot otherwise reach, which is the definition of a pivot.
 *
 * <p>It does not terminate TLS. A CONNECT tunnel is relayed opaquely, so the workload validates the target's
 * certificate itself and the platform never holds the plaintext. The cost is that the platform cannot see what
 * crosses an authorized tunnel; the benefit is that it also cannot be compelled to, and that a proxy
 * compromise does not yield tenant traffic. Egress policy is about destinations, not payloads.
 *
 * <h2>The order of operations is the security property</h2>
 *
 * <ol>
 *   <li>Read the request strictly, refusing anything with two readings.
 *   <li>Reduce the destination to canonical form, refusing a request whose request line and Host disagree.
 *   <li>Ask the control plane whether this capability may reach this destination, now.
 *   <li>Resolve the name once and classify every answer.
 *   <li>Connect to one of those exact addresses.
 * </ol>
 *
 * <p>Nothing is skipped when an earlier step is expensive and nothing is cached, because every one of these
 * can stop being true between two requests.
 */
public final class ProxyServer implements AutoCloseable {

    /** The header the sandbox presents its capability in. Never logged, never forwarded to the target. */
    static final String CREDENTIAL_HEADER = "proxy-authorization";

    /** Names a denial in a machine-readable way, so the synthetic workload need not parse prose. */
    static final String DENIAL_HEADER = "X-KaaS-Egress-Denial";

    /**
     * Headers a proxy consumes rather than forwards. Passing Proxy-Authorization upstream would hand the
     * platform's own bearer credential to the tenant's chosen target.
     */
    private static final List<String> HOP_BY_HOP = List.of(
            "proxy-authorization", "proxy-authenticate", "proxy-connection", "connection", "keep-alive",
            "te", "trailer", "transfer-encoding", "upgrade");

    private final ProxyConfiguration configuration;

    private final EgressAuthorizer authorizer;

    private final TargetResolver resolver;

    private final ProxyMetrics metrics = new ProxyMetrics();

    private final ServerSocket listener;

    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();

    private final ScheduledExecutorService supervisor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "egress-revalidation");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running = true;

    public ProxyServer(ProxyConfiguration configuration, EgressAuthorizer authorizer, TargetResolver resolver)
            throws IOException {
        this.configuration = configuration;
        this.authorizer = authorizer;
        this.resolver = resolver;
        this.listener = new ServerSocket();
        this.listener.setReuseAddress(true);
        this.listener.bind(new InetSocketAddress(configuration.listenPort()), 64);
    }

    public int port() {
        return listener.getLocalPort();
    }

    public ProxyMetrics metrics() {
        return metrics;
    }

    /** Accepts until closed. Each connection gets a virtual thread, so a slow target blocks only itself. */
    public void serve() {
        while (running) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException closed) {
                return;
            }
            connections.execute(() -> handle(client));
        }
    }

    private void handle(Socket client) {
        metrics.connectionOpened();
        try {
            client.setTcpNoDelay(true);
            ProxyRequest request = RequestReader.read(client.getInputStream());
            String credential = bearerCredential(request);
            if (credential == null) {
                // 407 rather than 403: the client is being told to authenticate to the proxy, which is a
                // different fact from the destination being disallowed, and conflating them would let a
                // missing credential read as a policy decision in the execution's evidence.
                respond(client, 407, "Proxy Authentication Required", DenialReason.CAPABILITY_INVALID);
                return;
            }
            if (request.isConnect()) {
                handleConnect(client, request, credential);
            } else {
                handleForward(client, request, credential);
            }
        } catch (MalformedRequest | MalformedDestination malformed) {
            metrics.denied(DenialReason.MALFORMED_REQUEST);
            respondQuietly(client, 400, "Bad Request", DenialReason.MALFORMED_REQUEST);
        } catch (ResolutionRefused refused) {
            metrics.denied(refused.reason());
            if (refused.addressClass() != null) {
                metrics.addressRefused(refused.addressClass());
            }
            respondQuietly(client, 403, "Forbidden", refused.reason());
        } catch (IOException | RuntimeException failure) {
            respondQuietly(client, 502, "Bad Gateway", DenialReason.TARGET_UNREACHABLE);
        } finally {
            metrics.connectionClosed();
            closeQuietly(client);
        }
    }

    private void handleConnect(Socket client, ProxyRequest request, String credential) throws IOException {
        RequestedTarget target = RequestedTarget.ofConnect(request);
        if (!permitted(client, credential, target)) {
            return;
        }
        ResolvedTarget resolved = resolver.resolve(target.destination());
        Socket upstream = connectToExactAddress(resolved, target.destination().port());
        if (upstream == null) {
            respond(client, 502, "Bad Gateway", DenialReason.TARGET_UNREACHABLE);
            return;
        }
        try {
            write(client, "HTTP/1.1 200 Connection Established\r\n\r\n");
            Tunnel tunnel = new Tunnel(client, upstream, credential, target.destination(), authorizer, metrics);
            tunnel.relay(connections, supervisor, configuration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(upstream);
        }
    }

    private void handleForward(Socket client, ProxyRequest request, String credential) throws IOException {
        RequestedTarget target = RequestedTarget.ofAbsoluteForm(request);
        if (!permitted(client, credential, target)) {
            return;
        }
        ResolvedTarget resolved = resolver.resolve(target.destination());
        Socket upstream = connectToExactAddress(resolved, target.destination().port());
        if (upstream == null) {
            respond(client, 502, "Bad Gateway", DenialReason.TARGET_UNREACHABLE);
            return;
        }
        try (upstream) {
            forwardRequest(upstream, request, target);
            // One request per connection, and the response is relayed to end of stream. The proxy does not
            // parse the response at all, which means it cannot disagree with the target about where the
            // response ends — the disagreement that response-splitting depends on. In particular it does not
            // follow a redirect: a 3xx is handed to the client unchanged, and if the client chooses to follow
            // it, that is a new request which arrives here and is authorized from scratch. The second
            // authorization is the enforcement point, not any inspection of the Location header.
            relayToEndOfStream(upstream, client);
        }
    }

    /**
     * @return true when the destination may be reached; otherwise the client has already been answered
     */
    private boolean permitted(Socket client, String credential, RequestedTarget target) throws IOException {
        metrics.authorizationRequested();
        AuthorizationDecision decision = authorizer.authorize(credential, target.destination());
        if (decision.authorized()) {
            return true;
        }
        metrics.denied(decision.reason());
        int status = switch (decision.reason()) {
            case AUTHORIZATION_UNAVAILABLE -> 503;
            case CAPABILITY_INVALID, CAPABILITY_EXPIRED -> 407;
            default -> 403;
        };
        respond(client, status, status == 503 ? "Service Unavailable" : "Forbidden", decision.reason());
        return false;
    }

    /**
     * Connects to an address that was already classified, never to a name.
     *
     * <p>{@code new Socket()} plus {@code connect(new InetSocketAddress(InetAddress, port))} is the whole of
     * it. The overload that takes a {@code String} host would resolve again inside the connect call, and that
     * second answer would reach a socket without ever reaching the classifier — the rebinding hole, reopened
     * by a one-word change. There is a mutation in the battery that makes exactly that change.
     */
    private Socket connectToExactAddress(ResolvedTarget resolved, int port) {
        for (InetAddress address : resolved.addresses()) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), (int) configuration.connectTimeout().toMillis());
                socket.setTcpNoDelay(true);
                return socket;
            } catch (IOException unreachable) {
                closeQuietly(socket);
            }
        }
        return null;
    }

    private void forwardRequest(Socket upstream, ProxyRequest request, RequestedTarget target) throws IOException {
        StringBuilder head = new StringBuilder();
        // Origin-form upstream: a target server is not a proxy and does not expect an absolute URI.
        head.append(request.method()).append(' ').append(target.originFormTarget()).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(target.destination().host()).append(':')
                .append(target.destination().port()).append("\r\n");
        for (ProxyRequest.Header header : request.headers()) {
            String name = header.lowerName();
            if (HOP_BY_HOP.contains(name) || "host".equals(name)) {
                continue;
            }
            head.append(header.name()).append(": ").append(header.value()).append("\r\n");
        }
        // Closing after one exchange means every request is authorized on its own. Reusing the upstream
        // connection would let a second request ride an authorization granted for the first.
        head.append("Connection: close\r\n\r\n");
        OutputStream out = upstream.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (request.body().length > 0) {
            out.write(request.body());
        }
        out.flush();
    }

    private static void relayToEndOfStream(Socket from, Socket to) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream();
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    /**
     * The bearer credential, or null if none was presented.
     *
     * <p>Read once here and passed by value. It is never put in a log, a metric, a header going upstream, or
     * an exception message; the sandbox is assumed to be able to read anything delivered into it, so the
     * credential's protection is that it authorizes exactly one execution at one epoch for one policy — but
     * that is not a reason to also leak it into somewhere it outlives the execution.
     */
    private static String bearerCredential(ProxyRequest request) {
        String header = request.singleHeader(CREDENTIAL_HEADER);
        if (header == null) {
            return null;
        }
        String prefix = "bearer ";
        if (header.length() <= prefix.length()
                || !header.substring(0, prefix.length()).toLowerCase(Locale.ROOT).equals(prefix)) {
            return null;
        }
        String token = header.substring(prefix.length()).strip();
        return token.isEmpty() ? null : token;
    }

    private void respond(Socket client, int status, String phrase, DenialReason reason) throws IOException {
        write(client, "HTTP/1.1 " + status + " " + phrase + "\r\n"
                + DENIAL_HEADER + ": " + reason.name() + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n");
    }

    private void respondQuietly(Socket client, int status, String phrase, DenialReason reason) {
        try {
            respond(client, status, phrase, reason);
        } catch (IOException gone) {
            // The client hung up before hearing the refusal. The refusal still happened.
        }
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do about a socket that is already gone.
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            listener.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        connections.shutdownNow();
        supervisor.shutdownNow();
        try {
            connections.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
