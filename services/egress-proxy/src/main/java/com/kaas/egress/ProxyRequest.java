package com.kaas.egress;

import java.util.List;
import java.util.Locale;

/**
 * One request as it arrived, before any of it has been believed.
 *
 * <p>Headers are kept as an ordered list of pairs rather than a map. A map has to decide what happens when a
 * name appears twice, and every such decision — first wins, last wins, join with commas — is a choice about
 * which of two possible requests this is. Keeping the list means the ambiguity is still visible at the point
 * where it can be refused.
 */
public record ProxyRequest(String method, String target, String version, List<Header> headers, byte[] body) {

    public record Header(String name, String value) {
        /** Lower-cased with ASCII rules for comparison; header names are case-insensitive but not locale-sensitive. */
        public String lowerName() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The single value of a header, or null if absent.
     *
     * @throws MalformedRequest if the header appears more than once
     */
    public String singleHeader(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        String found = null;
        for (Header header : headers) {
            if (header.lowerName().equals(wanted)) {
                if (found != null) {
                    // Duplicate Host is the classic authority-confusion primitive, and duplicate
                    // Content-Length is the classic smuggling one. Neither is resolved here.
                    throw new MalformedRequest("A request carries at most one " + wanted + " header.");
                }
                found = header.value();
            }
        }
        return found;
    }

    public boolean isConnect() {
        return "CONNECT".equals(method);
    }
}
