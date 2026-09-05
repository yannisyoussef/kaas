package com.kaas.egress;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

/**
 * A real authoritative DNS server, speaking the real protocol over TCP.
 *
 * <p>Not a mock of a resolver. A mock would prove that the proxy calls whatever it was given and nothing about
 * what actually leaves the process: the count of queries, whether a name is asked about twice, and what
 * happens when two consecutive queries for one name give different answers are all protocol-level facts, and a
 * mock of the application's own resolver interface cannot observe any of them.
 *
 * <p>Answers are programmable per name and can be made to change between queries, which is what makes a
 * rebinding scenario testable at all. Every query is counted and recorded, so a test can assert that exactly
 * one resolution happened for a connection rather than inferring it.
 */
public final class TestDnsServer implements AutoCloseable {

    private final ServerSocket listener;

    private final Thread acceptor;

    /** Answers per name. A queue: each query consumes the next entry, the last one repeating forever. */
    private final Map<String, List<String>> answers = new ConcurrentHashMap<>();

    private final List<String> queries = new CopyOnWriteArrayList<>();

    private final AtomicInteger queryCount = new AtomicInteger();

    private volatile boolean running = true;

    public TestDnsServer() throws IOException {
        this(true);
    }

    /**
     * @param loopbackOnly true for an in-process test; false when a container has to reach it
     *     <p>A container reaching the host arrives at the host's gateway address, not at its loopback, so a
     *     loopback-bound server is invisible to it on Linux — every query times out and every destination
     *     reports DNS_FAILED, which looks exactly like the proxy working correctly on a name that does not
     *     exist. Binding wide is a test-only concession, on an ephemeral port, for the topology suite.
     */
    public TestDnsServer(boolean loopbackOnly) throws IOException {
        this.listener = loopbackOnly
                ? new ServerSocket(0, 16, InetAddress.getLoopbackAddress())
                : new ServerSocket(0, 16);
        this.acceptor = new Thread(this::accept, "test-dns");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort());
    }

    /** The port, for a peer that has to reach this server by some other address than loopback. */
    public int port() {
        return listener.getLocalPort();
    }

    /** Every query for {@code name} answers with these addresses, in one response. */
    public TestDnsServer answering(String name, String... addresses) {
        answers.put(fqdn(name), List.of(String.join(",", addresses)));
        return this;
    }

    /**
     * Successive queries for {@code name} answer differently: the first query gets the first entry, and so on,
     * with the last repeating. This is how a DNS rebinding attempt is expressed.
     */
    public TestDnsServer answeringInTurn(String name, String... successiveAddresses) {
        answers.put(fqdn(name), new ArrayList<>(List.of(successiveAddresses)));
        return this;
    }

    public int queryCount() {
        return queryCount.get();
    }

    public List<String> queries() {
        return List.copyOf(queries);
    }

    public void resetCounters() {
        queryCount.set(0);
        queries.clear();
    }

    private static String fqdn(String name) {
        return name.endsWith(".") ? name : name + ".";
    }

    private void accept() {
        while (running) {
            try (Socket socket = listener.accept()) {
                serve(socket);
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        while (true) {
            int length;
            try {
                // DNS over TCP frames each message with a two-byte length prefix.
                length = in.readUnsignedShort();
            } catch (IOException endOfStream) {
                return;
            }
            byte[] raw = in.readNBytes(length);
            Message query = new Message(raw);
            Record question = query.getQuestion();
            queryCount.incrementAndGet();
            queries.add(question.getName().toString() + "/" + org.xbill.DNS.Type.string(question.getType()));

            Message response = new Message(query.getHeader().getID());
            response.getHeader().setFlag(Flags.QR);
            response.getHeader().setFlag(Flags.AA);
            response.addRecord(question, Section.QUESTION);

            List<String> programmed = answers.get(question.getName().toString());
            if (programmed == null || programmed.isEmpty()) {
                response.getHeader().setRcode(Rcode.NXDOMAIN);
            } else {
                // Consume one entry unless it is the last, which repeats. A test that programs a single
                // answer therefore gets a stable name, and one that programs a sequence gets a changing one.
                String entry = programmed.size() == 1 ? programmed.get(0) : programmed.remove(0);
                if (!entry.isEmpty()) {
                    for (String address : entry.split(",")) {
                        response.addRecord(
                                new ARecord(
                                        question.getName(),
                                        DClass.IN,
                                        1,
                                        InetAddress.getByName(address.trim())),
                                Section.ANSWER);
                    }
                }
                // An empty entry means NOERROR with no answer, which is what an AAAA-only name looks like to
                // an A query. That is a different failure from NXDOMAIN and worth being able to express.
            }

            byte[] encoded = response.toWire();
            out.writeShort(encoded.length);
            out.write(encoded);
            out.flush();
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        listener.close();
    }
}
