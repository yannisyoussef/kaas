package com.kaas.egress;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/**
 * Resolves a destination once, classifies every answer, and hands back addresses rather than a name.
 *
 * <h2>Why this is not {@code InetAddress.getByName}</h2>
 *
 * <p>The JDK resolver offers no control over which server is queried, no visibility of the full answer set,
 * and a process-global positive cache whose TTL is a deployment setting rather than a code property. A
 * security control cannot be built on any of the three: not knowing the full answer set means classifying one
 * address while the socket layer may use another, and a shared cache means one execution's resolution decides
 * another's.
 *
 * <p>The query is built and sent explicitly, with no cache object anywhere in the path, so "one resolution per
 * connection" is a property of this code rather than a hope about cache state — and it is observable at the
 * DNS server, which is where the tests measure it.
 *
 * <h2>Why only A records</h2>
 *
 * <p>v1 carries IPv4 only, so an AAAA answer could never be connected to. Querying for one anyway would mean
 * either ignoring the result — pure cost — or refusing destinations that merely happen to publish IPv6, which
 * would make every dual-stack destination unusable. Asking only for what can be used means an AAAA-only
 * destination fails with {@link DenialReason#DNS_FAILED} because nothing usable came back, which is honest and
 * fail-closed, and it means exactly one query leaves this process per connection.
 *
 * <h2>Multiple answers</h2>
 *
 * <p>If <em>any</em> returned address is outside global unicast, the whole resolution is refused — not just
 * that address. Skipping the bad one and connecting to a good one would work, and would also mean a name that
 * resolves partly into private space is treated as an ordinary external destination. An answer set mixing
 * public and private addresses is the shape of a rebinding attempt, and the conservative reading of an
 * ambiguous answer is to refuse it.
 */
public final class TargetResolver {

    private final SimpleResolver resolver;

    public TargetResolver(InetSocketAddress server, Duration timeout) {
        this.resolver = new SimpleResolver(server);
        this.resolver.setTimeout(timeout);
        // TCP, not UDP. A UDP answer is trivially forgeable by anything on the path, and the path here
        // includes an entire container network. TCP is not authentication either, but it removes off-path
        // spoofing, and the query volume of one lookup per connection makes the cost irrelevant.
        this.resolver.setTCP(true);
    }

    public ResolvedTarget resolve(CanonicalDestination destination) {
        Name name;
        try {
            // Fully qualified explicitly. Without the trailing dot the query would be subject to a search
            // list, and a search list turns one name into several — which is one more way for the name that
            // was authorized to differ from the name that was asked about.
            name = Name.fromString(destination.host() + ".");
        } catch (TextParseException notAName) {
            throw new ResolutionRefused(DenialReason.DNS_FAILED, null, "The destination is not a DNS name.");
        }

        Message response;
        try {
            response = resolver.send(Message.newQuery(Record.newRecord(name, Type.A, DClass.IN)));
        } catch (Exception unreachable) {
            // Includes timeouts. A resolver that cannot answer is not a resolver that answered "no", but both
            // end the same way here: nothing is connected to.
            throw new ResolutionRefused(DenialReason.DNS_FAILED, null, "The destination could not be resolved.");
        }
        if (response.getRcode() != Rcode.NOERROR) {
            throw new ResolutionRefused(DenialReason.DNS_FAILED, null, "The destination could not be resolved.");
        }

        List<InetAddress> permitted = new ArrayList<>();
        for (Record record : response.getSection(Section.ANSWER)) {
            if (!(record instanceof ARecord answer)) {
                // CNAMEs and anything else in the answer section are not addresses. A recursive resolver
                // returns the A records of the chain alongside them, so the chain is followed by the resolver
                // and never by this code.
                continue;
            }
            byte[] raw = answer.getAddress().getAddress();
            AddressClass classification = AddressPolicy.classify(raw);
            if (!classification.permitted()) {
                throw new ResolutionRefused(
                        DenialReason.ADDRESS_NOT_GLOBAL,
                        classification,
                        "The destination resolved to an address outside global unicast.");
            }
            try {
                // Built from bytes with an explicit name. This constructor performs no lookup — it cannot,
                // the address is already given — so the object handed to connect() is exactly the one that
                // was classified. Passing a hostname to a connect call instead would re-resolve, and the
                // second answer would never be classified. That is the whole of the rebinding defence.
                permitted.add(InetAddress.getByAddress(destination.host(), raw));
            } catch (UnknownHostException impossible) {
                throw new IllegalStateException("Four bytes are an address.", impossible);
            }
        }

        if (permitted.isEmpty()) {
            throw new ResolutionRefused(
                    DenialReason.DNS_FAILED, null, "The destination resolved to no usable address.");
        }
        return new ResolvedTarget(destination, List.copyOf(permitted));
    }
}
