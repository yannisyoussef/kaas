# Engine frame (runner → engine adapter, container standard input)

Written by the runner (`EngineInput`) immediately after the source frame on the sandbox's standard input,
and read by the Karate adapter (`KaasKarateAdapter.EngineFrame`) after the source bootstrap has frozen the
source filesystem, dropped every capability and exec'd it. The bootstrap reads exactly the source frame
with unbuffered `read(2)` calls, so the privileged program never reads these bytes.

```
offset  size  field
0       8     magic         "KAASENG1" (ASCII)
8       4     secretCount   u32 big-endian, 0..50
then secretCount entries, keys ascending:
        2     keyLength     u16 big-endian, 1..128
        n     key           ASCII binding key
        4     valueLength   u32 big-endian, 1..8192 (all values together at most 65536)
        n     value         UTF-8 bytes
then:
        1     egress        0 = none, 1 = present
if egress = 1:
        2     hostLength    u16 big-endian
        n     host          ASCII: the proxy's address on the execution's internal network
        2     port          u16 big-endian
        2     tokenLength   u16 big-endian
        n     token         ASCII: the execution's egress capability
then:
        8     trailer       "KAASEND1" (ASCII)
```

Every engine run receives a frame — a secret-free run with `secretCount = 0` — so the adapter behaves
identically whether or not a run has secrets. The adapter:

1. reads the frame exactly, refusing any deviation (and reporting `engine_error=SECRET_CHANNEL`);
2. requires the pipe to be empty after the trailer;
3. closes standard input (the JVM re-points descriptor 0 at `/dev/null`) and replaces `System.in`;
4. prints `kaas.secrets=CONSUMED`;
5. only then starts Karate, binding the global `kaas` = `{secrets: {<KEY>: <value>}, egress?: {uri, token}}`.

Tenant code reads a secret as `kaas.secrets.<KEY>`. It is intentionally readable by every scenario; its
protection is authorization, scope, runtime isolation, lifetime and the platform's output redaction — not
Java visibility. The runner's output redactor is registered with every value and the egress token.
