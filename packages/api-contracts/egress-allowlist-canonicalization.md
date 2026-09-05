# Egress allowlist canonicalization

**Status: NORMATIVE.** Two independent implementations must agree on every rule below: the control plane, which
parses and stores an allowlist entry, and the trusted egress proxy, which parses an incoming request target and
decides whether it matches one.

They agree by both implementing this document, not by sharing code. The proxy is trusted infrastructure that
must not carry a runtime dependency on control-plane implementation, and a shared library would make a
control-plane change silently a proxy change. Two implementations of one written rule can be tested against
each other; one implementation agreeing with itself proves nothing.

## Why canonicalization is a security control here

An allowlist decides whether a request leaves the platform. If the form stored by the control plane and the
form parsed by the proxy differ in any way, the proxy either refuses traffic the tenant authorized or — the
direction that matters — permits traffic it did not. Every rule below exists because some pair of spellings
would otherwise denote the same destination while comparing unequal, or different destinations while comparing
equal.

## Entry grammar

An allowlist entry is exactly:

```
host ":" port "/" scheme
```

There is no wildcard, no CIDR, no port range, no path, and no scheme-relative form. `*`, `*.example.com`,
`0.0.0.0/0`, and `::/0` are not entries and must be refused at parse time rather than matched and denied later.

## Host

1. **ASCII only.** A host containing any byte outside `[A-Za-z0-9.-]` is refused. Unicode is not decoded,
   normalized, or transformed — an internationalized name must be supplied already in punycode by the tenant.
   Accepting Unicode would mean the control plane and the proxy each running an IDNA implementation, and two
   IDNA implementations at different library versions is precisely the disagreement this document exists to
   prevent.
2. **Lower-cased** using ASCII rules only. `EXAMPLE.com` and `example.COM` are the same host. Locale-sensitive
   lower-casing is forbidden: in a Turkish locale `String.toLowerCase()` maps `I` to `ı`, so the same entry
   would canonicalize differently depending on the JVM's default locale.
3. **No trailing dot.** `example.com.` and `example.com` are the same host; the stored form has no trailing
   dot. A trailing dot is otherwise a second spelling of every name.
4. **No empty labels.** `example..com`, `.example.com`, and the empty host are refused.
5. **Label length** 1–63 bytes. **Total length** at most 253 bytes after canonicalization.
6. **No userinfo, no percent-encoding, no brackets, no whitespace.** `user@example.com`,
   `exam%70le.com`, and `[example.com]` are refused rather than decoded — decoding is where two parsers
   disagree.
7. **IP literals are refused in v1**, both IPv4 and IPv6, in every form including IPv4-mapped IPv6 and
   zero-compressed IPv6. An entry names a hostname the proxy will resolve; an IP literal skips the resolution
   whose result the address classifier exists to inspect. This restriction is a v1 narrowing, not a permanent
   property, and lifting it requires the classifier to run on the literal itself.

A host is refused if it is not already in canonical form. The control plane does not silently rewrite what a
tenant supplied: rejecting `EXAMPLE.com.` with a reason is honest, and accepting it while storing something
else means the tenant's stated intent and the enforced policy are two different strings.

## Port

An explicit integer, 1–65535. There is no default and no inference from the scheme: `https` does not imply
`443`. A destination the tenant did not write down is a destination the tenant did not authorize.

## Scheme

`HTTP` or `HTTPS`, upper-case in the stored form. Nothing else — no `ftp`, no `ws`, no raw TCP. The scheme is
part of the entry rather than derived from the port, because `443/HTTP` and `443/HTTPS` are different
propositions about what the proxy will do.

## Matching

A request matches an entry when the canonicalized request host, the port, and the scheme class are all equal.
Equality is byte equality on the canonical forms — never a suffix, prefix, or substring test.

For `HTTPS` the request arrives as an HTTP `CONNECT` whose authority carries the host and port. For `HTTP` the
request target carries them. In both cases the proxy canonicalizes by these rules before comparing, and refuses
a request whose host is not already canonical, exactly as the control plane refuses a non-canonical entry.

## Digest

An allowlist's canonical digest covers its entries in sorted order — by host, then port, then scheme — each
component length-prefixed before hashing. Sorting is required so that two policies listing the same
destinations in different orders are the same policy, and length-prefixing is required so that no rearrangement
of characters across component boundaries can produce the same preimage.
