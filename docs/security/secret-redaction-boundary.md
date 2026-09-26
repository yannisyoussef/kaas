# The secret redaction boundary

## Guaranteed

**Exact registered plaintext byte sequences are removed from every platform-owned output channel KaaS keeps.**
The registered sequences are the values of the run's pinned secrets, exactly as delivered, and the execution's
egress credential. The covered channels are everything the runner keeps of a sandbox's stdout and stderr — the
redacted transcripts and the observation map — and everything built from them.

This holds:

- across **any chunking**: Docker frame boundaries are arbitrary, and a value split across frames, one byte per
  frame in the extreme, is found (the redactor holds back `longest − 1` bytes per stream);
- at the **end of a stream**: a value ending in the last bytes a sandbox ever prints is redacted, and a
  non-secret tail held back is released;
- at the **output ceiling**: redaction happens before the ceiling is charged, so a value that crosses it is
  replaced before anything is counted and cannot leave a prefix;
- for **overlapping values**: covered bytes are merged into maximal runs, so `abc` and `abcdef` registered
  together never leave `def` behind;
- for **multi-byte and multiline values**: matching is on raw bytes, before decoding, so CRLF, LF, Unicode and
  PEM blocks are matched exactly and never normalised;
- on **both streams independently**, with separate state;
- in **linear time**, whatever the values: an Aho-Corasick automaton over bytes, so a tenant who knows its own
  secret cannot make the runner quadratic.

The replacement is always `[REDACTED]` — never a key, a length, a version, or which value matched.

Instrumented, not assumed: the redactor counts the raw occurrences it replaced, per stream. The CI gate requires
those counts to be positive in the tests that deliberately print a value **and** the kept output to be free of
it. A kept output with no secret proves nothing unless the redactor also saw one arrive.

## Not guaranteed

Detection of transformed data: Base64, hex, hashes, reversal, character splitting, interleaving, custom encoding,
encryption, or anything else that is not the exact byte sequence. Hostile tenant code can print any of these.
Nor does redaction constrain where the sandbox sends data: an authorized destination can receive a secret on
purpose. Network policy controls **where**; redaction controls **what KaaS keeps**. This is not data-loss
prevention.

## The result protocol is outside the redactor

The engine adapter's protocol lines (engine identity, secret-channel confirmation, verdict, engine error
category) are read on a separate branch from the **raw** stream, before redaction, by a scanner that keeps only
counts and closed-vocabulary words. So a secret that happens to equal `PASSED` cannot change a verdict, and a
forged protocol line carrying a secret is refused (a duplicate or non-vocabulary value) without its content
ever being stored or echoed in an error.

## Docker's log store

For every sandbox that runs tenant code, `LogConfig=none`: the daemon writes nothing to disk and `docker logs`
has nothing to return. Output reaches the runner only over a live attach, into the trusted collector.
Measured by the secret gate while the container is alive: the log driver is `none`, the container has no log
path, the daemon refuses to return logs, and the container's metadata does not contain the value.

Platform-only probe sandboxes, which run no tenant code and receive no secret, keep the bounded json-file.

## Surfaces outside this boundary, and how each is handled

| Surface | Handling |
| --- | --- |
| Egress proxy logs | structural: the proxy writes nothing a request carries (headers, cookies, path, query, body); dnsjava held at ERROR. No registry of secrets is sent to the proxy. |
| Control-plane logs | categories and identifiers only; unexpected exceptions on secret paths are logged without a cause chain. |
| Runner exceptions and failure details | categories from closed vocabularies; a malformed protocol line is never quoted. |
| Test reports | assertions compare secrets into booleans; CI scans every build output for the run's nonce. |
