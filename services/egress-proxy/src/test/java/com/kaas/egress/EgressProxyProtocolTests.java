package com.kaas.egress;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proxy's behaviour against a real DNS server and real sockets, for every path that ends in a refusal.
 *
 * <p>The success path — bytes actually reaching a target — is not here, and the reason is worth stating. The
 * address classifier permits global unicast only, and a test process can bind a listener on loopback or on
 * whatever private address the host happens to have; neither is global. Making the success path testable
 * in-process would mean giving the classifier a test-only exemption, which is exactly the "weaken the
 * production classifier so local tests can reach an RFC1918 container" mistake. So the success path is proven
 * in the Docker topology suite, where containers can be given addresses the production classifier accepts, and
 * this suite proves everything that happens before a byte would be relayed.
 *
 * <p>What this suite can still prove about the success path is that a request survives every check: an
 * authorized destination resolving to a global address reaches the connect attempt, and fails there with
 * TARGET_UNREACHABLE because nothing is listening. That is a different outcome from every refusal, so "got all
 * the way through policy" is observable here.
 */
@DisplayName("Egress proxy protocol and policy surface")
class EgressProxyProtocolTests {

    private static final String TOKEN = "kaas-egress-capability-sentinel-value";

    /**
     * Global unicast, and unreachable from any test host. 11.0.0.0/8 is allocated but not publicly routed, so
     * a connection attempt to it fails rather than leaving the machine — while still being exactly the class
     * of address the production classifier permits, which is the property under test.
     */
    private static final String GLOBAL = "11.0.0.7";

    private TestDnsServer dns;

    private RecordingAuthorizer authorizer;

    private ProxyServer proxy;

    @BeforeEach
    void start() throws IOException {
        dns = new TestDnsServer();
        authorizer = new RecordingAuthorizer();
        ProxyConfiguration configuration = new ProxyConfiguration(
                0,
                dns.address(),
                "http://control-plane.invalid",
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofMillis(750));
        proxy = new ProxyServer(configuration, authorizer, new TargetResolver(dns.address(), Duration.ofSeconds(5)));
        Thread server = new Thread(proxy::serve, "proxy-under-test");
        server.setDaemon(true);
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        proxy.close();
        dns.close();
    }

    private ProxyClient.Response send(String raw) throws IOException {
        return ProxyClient.send(proxy.port(), raw);
    }

    // ---------------------------------------------------------------- credential

    @Test
    @DisplayName("a request with no capability is refused before anything else happens")
    void aRequestWithoutACapabilityIsRefused() throws IOException {
        dns.answering("api.example.com", GLOBAL);
        ProxyClient.Response response =
                send("CONNECT api.example.com:443 HTTP/1.1\r\nHost: api.example.com:443\r\n\r\n");

        assertThat(response.status()).isEqualTo(407);
        assertThat(response.denialReason()).isEqualTo("CAPABILITY_INVALID");
        // Nothing was asked and nothing was resolved. A proxy that resolved first would be a name-lookup
        // oracle for anyone who can reach it, with no credential at all.
        assertThat(authorizer.asked()).isEmpty();
        assertThat(dns.queryCount()).isZero();
    }

    @Test
    @DisplayName("a credential in the wrong scheme is no credential")
    void aMalformedCredentialIsRefused() throws IOException {
        for (String header : new String[] {"Basic " + TOKEN, "Bearer", "Bearer ", "" + TOKEN, "bearer"}) {
            ProxyClient.Response response = send("CONNECT api.example.com:443 HTTP/1.1\r\n"
                    + "Host: api.example.com:443\r\n"
                    + "Proxy-Authorization: " + header + "\r\n\r\n");
            assertThat(response.status()).as("header %s", header).isEqualTo(407);
        }
    }

    @Test
    @DisplayName("the credential is presented to the authority exactly as received and goes nowhere else")
    void theCredentialReachesOnlyTheAuthority() throws IOException {
        dns.answering("api.example.com", GLOBAL);
        send(ProxyClient.connect("api.example.com:443", TOKEN));

        assertThat(authorizer.asked()).singleElement()
                .satisfies(question -> assertThat(question.token()).isEqualTo(TOKEN));
    }

    // ---------------------------------------------------------------- ordering

    @Test
    @DisplayName("authorization happens before resolution, so a denied name is never even looked up")
    void authorizationPrecedesResolution() throws IOException {
        dns.answering("denied.example.com", GLOBAL);
        authorizer.deny(DenialReason.DESTINATION_NOT_ALLOWED);

        ProxyClient.Response response = send(ProxyClient.connect("denied.example.com:443", TOKEN));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.denialReason()).isEqualTo("DESTINATION_NOT_ALLOWED");
        // The ordering is the claim. If resolution came first, a workload could use the proxy to enumerate
        // internal DNS for destinations it is not allowed to reach, and the denial would leak whether a name
        // exists. Zero queries is what makes that impossible rather than merely unlikely.
        assertThat(dns.queryCount()).isZero();
    }

    @Test
    @DisplayName("the destination asked about is the canonical destination, not the string the client sent")
    void theAuthorityIsAskedAboutTheCanonicalDestination() throws IOException {
        dns.answering("api.example.com", GLOBAL);
        send(ProxyClient.connect("api.example.com:443", TOKEN));
        assertThat(authorizer.asked()).singleElement()
                .satisfies(question -> assertThat(question.destination()).isEqualTo("api.example.com:443/HTTPS"));

        send(ProxyClient.get("http://api.example.com/thing?q=1", "api.example.com", TOKEN));
        assertThat(authorizer.asked()).last()
                .satisfies(question -> assertThat(question.destination()).isEqualTo("api.example.com:80/HTTP"));
    }

    @Test
    @DisplayName("a tunnel to a port allowlisted for HTTP is denied, so the scheme is load-bearing")
    void aTunnelToAnHttpPortIsDenied() throws IOException {
        dns.answering("api.example.com", GLOBAL);
        // The policy permits plain HTTP on port 80. A CONNECT to the same port asks for an opaque byte
        // channel, which is not what that entry authorized — and an opaque channel to an HTTP port is a way
        // to obtain arbitrary TCP to a destination the tenant only meant to make ordinary requests to.
        authorizer.allowOnly("api.example.com:80/HTTP");

        ProxyClient.Response response = send(ProxyClient.connect("api.example.com:80", TOKEN));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.denialReason()).isEqualTo("DESTINATION_NOT_ALLOWED");
    }

    // ---------------------------------------------------------------- authority disagreement

    @Test
    @DisplayName("a request that names two different destinations is refused rather than reconciled")
    void aRequestNamingTwoDestinationsIsRefused() throws IOException {
        dns.answering("allowed.example.com", GLOBAL);
        dns.answering("internal.example.com", GLOBAL);
        authorizer.allowOnly("allowed.example.com:80/HTTP");

        // Authorize the URL, connect with the Host — or the reverse. Whichever way round a downstream reader
        // resolves it, one of the two destinations was never authorized.
        ProxyClient.Response response =
                send(ProxyClient.get("http://allowed.example.com/x", "internal.example.com", TOKEN));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.denialReason()).isEqualTo("MALFORMED_REQUEST");
        assertThat(authorizer.asked()).isEmpty();
        assertThat(dns.queryCount()).isZero();
    }

    @Test
    @DisplayName("duplicate Host headers are refused rather than one of them chosen")
    void duplicateHostHeadersAreRefused() throws IOException {
        ProxyClient.Response response = send("GET http://allowed.example.com/x HTTP/1.1\r\n"
                + "Host: allowed.example.com\r\n"
                + "Host: internal.example.com\r\n"
                + "Proxy-Authorization: Bearer " + TOKEN + "\r\n\r\n");

        assertThat(response.status()).isEqualTo(400);
        assertThat(authorizer.asked()).isEmpty();
    }

    @Test
    @DisplayName("framing that two readers would disagree about is refused")
    void ambiguousFramingIsRefused() throws IOException {
        String[] hostile = {
            // Bare LF: splits into two headers for one reader and stays one for another.
            "GET http://allowed.example.com/x HTTP/1.1\r\nHost: allowed.example.com\nProxy-Authorization: Bearer "
                    + TOKEN + "\r\n\r\n",
            // Obsolete folding hides a header from whichever reader does not implement it.
            "GET http://allowed.example.com/x HTTP/1.1\r\nHost: allowed.example.com\r\n Host: evil.example\r\n"
                    + "Proxy-Authorization: Bearer " + TOKEN + "\r\n\r\n",
            // Whitespace before the colon: "Host :" and "Host:" are the same header to some readers.
            "GET http://allowed.example.com/x HTTP/1.1\r\nHost : allowed.example.com\r\n"
                    + "Proxy-Authorization: Bearer " + TOKEN + "\r\n\r\n",
            // Transfer-Encoding is refused outright; nearly every smuggling technique is a chunked framing
            // disagreement, and this proxy carries one request per connection and never needs it.
            "POST http://allowed.example.com/x HTTP/1.1\r\nHost: allowed.example.com\r\n"
                    + "Transfer-Encoding: chunked\r\nProxy-Authorization: Bearer " + TOKEN + "\r\n\r\n0\r\n\r\n",
            // Two Content-Lengths, the other half of the smuggling pair.
            "POST http://allowed.example.com/x HTTP/1.1\r\nHost: allowed.example.com\r\n"
                    + "Content-Length: 0\r\nContent-Length: 5\r\nProxy-Authorization: Bearer " + TOKEN + "\r\n\r\n",
            // Two spaces in the request line: some servers read the target as " /x".
            "GET  http://allowed.example.com/x  HTTP/1.1\r\nHost: allowed.example.com\r\n"
                    + "Proxy-Authorization: Bearer " + TOKEN + "\r\n\r\n",
            // Origin-form names no destination at all, so a proxy accepting it would have to trust Host alone.
            "GET /x HTTP/1.1\r\nHost: allowed.example.com\r\nProxy-Authorization: Bearer " + TOKEN + "\r\n\r\n",
            // A CONNECT carrying a URL rather than an authority.
            "CONNECT http://allowed.example.com:443/ HTTP/1.1\r\nHost: allowed.example.com:443\r\n"
                    + "Proxy-Authorization: Bearer " + TOKEN + "\r\n\r\n",
            // An https absolute-form request asks the proxy to terminate TLS, which it will not do.
            "GET https://allowed.example.com/x HTTP/1.1\r\nHost: allowed.example.com\r\n"
                    + "Proxy-Authorization: Bearer " + TOKEN + "\r\n\r\n",
        };
        for (String raw : hostile) {
            ProxyClient.Response response = send(raw);
            assertThat(response.status())
                    .as("request %s", raw.replace("\r", "\\r").replace("\n", "\\n"))
                    .isEqualTo(400);
        }
        assertThat(authorizer.asked()).isEmpty();
        assertThat(dns.queryCount()).isZero();
    }

    // ---------------------------------------------------------------- address classification

    @Test
    @DisplayName("a name resolving outside global unicast is refused, whatever it resolves to")
    void aNonGlobalAnswerIsRefused() throws IOException {
        String[] unsafe = {
            "127.0.0.1", "10.0.0.5", "172.16.0.5", "192.168.1.5", "169.254.169.254", "169.254.0.1",
            "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.2.5", "198.51.100.5",
            "203.0.113.5", "198.18.0.5", "192.0.0.5", "192.88.99.5", "240.0.0.5",
        };
        for (String address : unsafe) {
            dns.answering("target.example.com", address);
            ProxyClient.Response response = send(ProxyClient.connect("target.example.com:443", TOKEN));
            assertThat(response.status()).as("resolved to %s", address).isEqualTo(403);
            assertThat(response.denialReason()).as("resolved to %s", address).isEqualTo("ADDRESS_NOT_GLOBAL");
        }
    }

    @Test
    @DisplayName("cloud instance metadata is refused through a name that resolves to it")
    void metadataThroughANameIsRefused() throws IOException {
        // The range rule covers this, but the impact of it being reachable is credential theft on most cloud
        // providers, so it is pinned by name as well: a policy allowlisting an innocuous hostname whose owner
        // points it at 169.254.169.254 is the shortest path from "tenant can name a destination" to "tenant
        // holds the platform's instance credentials".
        dns.answering("metadata.example.com", "169.254.169.254");
        ProxyClient.Response response = send(ProxyClient.connect("metadata.example.com:80", TOKEN));
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.denialReason()).isEqualTo("ADDRESS_NOT_GLOBAL");
    }

    @Test
    @DisplayName("an answer mixing safe and unsafe addresses is refused entirely")
    void aMixedAnswerIsRefused() throws IOException {
        // Connecting to the safe one and ignoring the rest would work. It would also mean a name that resolves
        // partly into private space is treated as an ordinary destination, and an answer set mixing public and
        // private addresses is the shape of a rebinding attempt rather than of a healthy service.
        dns.answering("mixed.example.com", GLOBAL, "10.0.0.5");
        assertThat(send(ProxyClient.connect("mixed.example.com:443", TOKEN)).denialReason())
                .isEqualTo("ADDRESS_NOT_GLOBAL");

        // Order must not matter: the unsafe address first is the same answer.
        dns.answering("mixed2.example.com", "10.0.0.5", GLOBAL);
        assertThat(send(ProxyClient.connect("mixed2.example.com:443", TOKEN)).denialReason())
                .isEqualTo("ADDRESS_NOT_GLOBAL");
    }

    @Test
    @DisplayName("a name that does not resolve, or resolves to nothing usable, is refused")
    void anUnresolvableNameIsRefused() throws IOException {
        ProxyClient.Response missing = send(ProxyClient.connect("nowhere.example.com:443", TOKEN));
        assertThat(missing.status()).isEqualTo(403);
        assertThat(missing.denialReason()).isEqualTo("DNS_FAILED");

        // NOERROR with an empty answer section: what an AAAA-only destination looks like to an A query. v1
        // carries IPv4 only, so this is the honest outcome rather than a silent fallback to IPv6.
        dns.answering("v6only.example.com", "");
        ProxyClient.Response empty = send(ProxyClient.connect("v6only.example.com:443", TOKEN));
        assertThat(empty.status()).isEqualTo(403);
        assertThat(empty.denialReason()).isEqualTo("DNS_FAILED");
    }

    // ---------------------------------------------------------------- resolution count

    @Test
    @DisplayName("one connection causes exactly one resolution, measured at the DNS server")
    void oneConnectionResolvesOnce() throws IOException {
        dns.answering("api.example.com", GLOBAL);
        dns.resetCounters();

        ProxyClient.Response response = send(ProxyClient.connect("api.example.com:443", TOKEN));

        // Past policy and past classification, failing only because nothing is listening on a globally
        // classified address that is not routed. That is the whole of the pre-connect path having succeeded.
        assertThat(response.status()).isEqualTo(502);
        assertThat(response.denialReason()).isEqualTo("TARGET_UNREACHABLE");
        // The count is measured at the DNS server rather than reported by the application, because an
        // application cannot report a resolution it did not know it performed.
        //
        // On its own this count does NOT prove the proxy connects to the address it classified: an
        // implementation that connected by hostname would resolve through the JDK instead, which this server
        // never sees, and the count would still be one. That mutation survived this test until the separate
        // case below was written for it. The count proves that the proxy's own resolution happens once — not
        // where the connection went.
        assertThat(dns.queryCount()).isEqualTo(1);
        assertThat(dns.queries()).containsExactly("api.example.com./A");
    }

    @Test
    @DisplayName("the address that was classified is the address connected to, and no other")
    void theClassifiedAddressIsTheOneConnectedTo() throws IOException, InterruptedException {
        // The rebinding defence, stated as something observable. The name below has two different answers:
        // the controlled DNS server — the only resolver the proxy is supposed to consult — answers with a
        // global address nothing listens on, while the JDK's resolver answers with loopback, where this test
        // does listen. The two answers are what make the difference visible; with one answer, connecting by
        // name and connecting by address both fail and look identical.
        //
        // A proxy that connects to the classified InetAddress reaches nothing and reports TARGET_UNREACHABLE.
        // A proxy that passes the hostname to connect() re-resolves, reaches the listener, and reports a
        // successful tunnel — and that second answer was never classified, which is the entire hole.
        try (java.net.ServerSocket decoy =
                new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())) {
            java.util.concurrent.atomic.AtomicInteger reached = new java.util.concurrent.atomic.AtomicInteger();
            Thread listener = new Thread(() -> {
                while (true) {
                    try {
                        decoy.accept().close();
                        reached.incrementAndGet();
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            listener.setDaemon(true);
            listener.start();

            dns.answering("exact-address.example.com", GLOBAL);
            ProxyClient.Response response =
                    send(ProxyClient.connect("exact-address.example.com:" + decoy.getLocalPort(), TOKEN));

            assertThat(response.status()).isEqualTo(502);
            assertThat(response.denialReason()).isEqualTo("TARGET_UNREACHABLE");
            // The load-bearing assertion. Nothing must have arrived at the address the name resolves to by
            // any route other than the one the classifier inspected.
            assertThat(reached.get()).as("connections that reached the address the JDK resolves to").isZero();
        }
    }

    @Test
    @DisplayName("a name whose answer changes between queries is classified afresh on each connection")
    void eachConnectionIsClassifiedIndependently() throws IOException {
        // The rebinding scenario, at the protocol level: the same name answers safely, then unsafely. Each
        // connection performs its own resolution and its own classification, so the second is refused. The
        // first connection is not retroactively unsafe and the second does not inherit the first's verdict —
        // which is what "resolve once per connection" has to mean to be worth anything.
        dns.answeringInTurn("rebind.example.com", GLOBAL, "127.0.0.1", GLOBAL);
        dns.resetCounters();

        assertThat(send(ProxyClient.connect("rebind.example.com:443", TOKEN)).denialReason())
                .isEqualTo("TARGET_UNREACHABLE");
        assertThat(send(ProxyClient.connect("rebind.example.com:443", TOKEN)).denialReason())
                .isEqualTo("ADDRESS_NOT_GLOBAL");
        assertThat(send(ProxyClient.connect("rebind.example.com:443", TOKEN)).denialReason())
                .isEqualTo("TARGET_UNREACHABLE");
        assertThat(dns.queryCount()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- authority verdicts

    @Test
    @DisplayName("each denial reason from the authority produces its own truthful status")
    void eachDenialReasonIsReportedTruthfully() throws IOException {
        dns.answering("api.example.com", GLOBAL);
        record Case(DenialReason reason, int status) {}
        for (Case expected : new Case[] {
            new Case(DenialReason.DESTINATION_NOT_ALLOWED, 403),
            new Case(DenialReason.ASSIGNMENT_FENCED, 403),
            new Case(DenialReason.RUN_NOT_EXECUTING, 403),
            new Case(DenialReason.CAPABILITY_EXPIRED, 407),
            new Case(DenialReason.CAPABILITY_INVALID, 407),
            // Unavailable is 503 and not 403 on purpose: "I could not ask" is not "the answer was no", and an
            // execution's evidence should be able to tell an infrastructure failure from a policy decision.
            new Case(DenialReason.AUTHORIZATION_UNAVAILABLE, 503),
        }) {
            authorizer.deny(expected.reason());
            ProxyClient.Response response = send(ProxyClient.connect("api.example.com:443", TOKEN));
            assertThat(response.status()).as("%s", expected.reason()).isEqualTo(expected.status());
            assertThat(response.denialReason()).isEqualTo(expected.reason().name());
        }
        // No connection was attempted for any of them, and nothing was resolved.
        assertThat(dns.queryCount()).isZero();
    }

    @Test
    @DisplayName("an authority that fails outright refuses the request rather than allowing it")
    void anAuthorityThatThrowsIsARefusal() throws IOException {
        dns.answering("api.example.com", GLOBAL);
        authorizer.answerWith((token, destination) -> {
            throw new IllegalStateException("the control plane is unreachable");
        });

        ProxyClient.Response response = send(ProxyClient.connect("api.example.com:443", TOKEN));

        // Fail closed. Availability loss is preferable to carrying traffic on an authority nobody can confirm.
        assertThat(response.status()).isEqualTo(502);
        assertThat(dns.queryCount()).isZero();
    }

    // ---------------------------------------------------------------- metrics

    @Test
    @DisplayName("metrics carry only closed-enumeration dimensions, never a destination")
    void metricsCarryNoTenantData() throws IOException {
        dns.answering("secret-tenant-name.example.com", "10.0.0.5");
        send(ProxyClient.connect("secret-tenant-name.example.com:443", TOKEN));

        assertThat(proxy.metrics().snapshot().keySet())
                .allSatisfy(name -> assertThat(name)
                        .doesNotContain("secret-tenant-name")
                        .doesNotContain("10.0.0.5")
                        .doesNotContain(TOKEN));
        assertThat(proxy.metrics().count("kaas_egress_address_refused_total{class=PRIVATE_USE}")).isEqualTo(1);
    }
}
