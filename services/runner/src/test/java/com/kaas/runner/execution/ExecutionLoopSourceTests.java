package com.kaas.runner.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.runner.authority.ExecutionAuthority;
import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.command.CommandValidator;
import com.kaas.runner.command.ValidatedCommand;
import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.sandbox.SandboxLaunchRequest;
import com.kaas.runner.sandbox.SandboxLauncher;
import com.kaas.runner.sandbox.SandboxOutcome;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.kaas.runner.source.SourceBundle;
import com.kaas.runner.source.SourceBundleRejected;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The refusals on the source path, driven directly rather than through a whole pipeline.
 *
 * <h2>Why here and not in the pipeline suite</h2>
 *
 * <p>Every check on this path guards a window that a full end-to-end run cannot open on demand: authority
 * ending mid-transfer, a capability the control plane declines, a delivery arriving without one. A mutation
 * battery removed each of them and the pipeline stayed green — not because the pipeline is weak, but because
 * it can only exercise the states a healthy control plane produces.
 *
 * <p>So these call the loop's own source methods with the state that matters. The methods are package-private
 * rather than public: the seam is a test one, and nothing outside this package gains a way to redeem or stage
 * anything.
 */
@DisplayName("Execution loop source path")
class ExecutionLoopSourceTests {

    /** An authority that has already ended definitively, which is the state every check below is about. */
    /** Any digest-pinned reference. The profile refuses a tag, and nothing here ever pulls it. */
    private static final String PINNED_IMAGE = "busybox@sha256:" + "0".repeat(64);

    private static final ExecutionAuthority LOST = new ExecutionAuthority() {
        @Override
        public com.kaas.runner.authority.AuthorityDecision lostReason() {
            return com.kaas.runner.authority.AuthorityDecision.LEASE_EXPIRED;
        }

        @Override
        public java.time.Duration remainingBudget() {
            return java.time.Duration.ZERO;
        }
    };

    /** The platform's own retained authority, so the negative cases are compared against the real thing. */
    private static final ExecutionAuthority HELD = ExecutionAuthority.retained();

    @Test
    @DisplayName("a worker that lost its authority does not fetch tenant source")
    void authorityIsRequiredBeforeTheTransfer() {
        // Checked BEFORE the transfer, so a fenced worker does not spend a redemption -- a capability is
        // assignment-scoped and consuming one on behalf of an assignment that has moved is work done for a
        // run this host no longer serves.
        //
        // The client here is null on purpose: if the check were removed, this would fail on a null client
        // rather than passing, so the test cannot succeed for the wrong reason.
        ExecutionLoop loop = loopWithoutClient();

        assertThatThrownBy(() -> loop.redeemSource(command(), "kaas_src_" + "x".repeat(40), LOST))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.AUTHORITY_LOST);
    }

    @Test
    @DisplayName("a delivery carrying no source capability is refused, not attempted")
    void aDeliveryWithoutACapabilityIsRefused() {
        // Reachable: a run whose snapshot selected nothing, or a control plane that omitted the credential.
        // Refused as NOT_REDEEMABLE rather than attempted with nothing, which would put a null into a request
        // header and turn a clean refusal into a transport error nobody can act on.
        ExecutionLoop loop = loopWithoutClient();

        assertThatThrownBy(() -> loop.redeemSource(command(), null, HELD))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.NOT_REDEEMABLE);
        assertThatThrownBy(() -> loop.redeemSource(command(), "   ", HELD))
                .isInstanceOf(SourceBundleRejected.class);
    }

    @Test
    @DisplayName("a capability the control plane declines yields no bundle and no bytes")
    void aDeclinedCapabilityIsRefused() throws Exception {
        // The control plane refuses for reasons that are its business -- expired, fenced, cancelled,
        // superseded, or for a different assignment -- and answers with a status rather than a bundle. From
        // here that is simply not redeemable, and it must not become an empty bundle that stages nothing and
        // reports success.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/source-bundles", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        try {
            ExecutionLoop loop = loopWith(new ControlPlaneClient(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "Bearer test",
                    Duration.ofSeconds(5),
                    duration -> Thread.sleep(duration.toMillis())));

            assertThatThrownBy(() -> loop.redeemSource(command(), "kaas_src_" + "x".repeat(40), HELD))
                    .isInstanceOf(SourceBundleRejected.class)
                    .extracting(failure -> ((SourceBundleRejected) failure).reason())
                    .isEqualTo(SourceBundleRejected.Reason.NOT_REDEEMABLE);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a worker that lost its authority frames no tenant source for a sandbox")
    void authorityIsRequiredBeforeTheHandover() {
        // The last check on this path. Everything before it was a network transfer into memory; this is the
        // step that would put tenant bytes in front of a sandbox, and a worker fenced during provisioning must
        // not take it.
        //
        // The check is cheaper to state than it was in KAAS-18 and covers more: there is no host directory to
        // leave behind any more, because the bytes never reach a disk. What it prevents is a container being
        // built at all for a run this host no longer owns.
        ExecutionLoop loop = loopWithoutClient();
        SourceBundle bundle = bundle();

        assertThatThrownBy(() -> loop.frameSource(bundle, LOST))
                .isInstanceOf(SourceBundleRejected.class)
                .extracting(failure -> ((SourceBundleRejected) failure).reason())
                .isEqualTo(SourceBundleRejected.Reason.AUTHORITY_LOST);

        // ANTI-VACUITY. The same call with authority held produces a delivery, so the refusal above is about
        // the authority and not about a method that refuses everything.
        var delivery = loop.frameSource(bundle, HELD);
        assertThat(delivery).isNotNull();
        assertThat(delivery.frame()).isNotEmpty();
        assertThat(delivery.filesystemBytes()).isPositive();
    }

    @Test
    @DisplayName("the framed bundle carries the bytes and never a path the sandbox could be told to run")
    void theFrameCarriesBytesAndNothingExecutable() {
        // The frame is the entire channel tenant source travels on, so what it is NOT carrying matters as
        // much as what it is. There is no field for a mode, an interpreter, an entrypoint or a command,
        // because there is nowhere in the format to put one -- and the bootstrap that reads it takes its
        // target, its modes and the program it hands over to from compile-time constants.
        byte[] frame = loopWithoutClient().frameSource(bundle(), HELD).frame();

        assertThat(new String(frame, StandardCharsets.ISO_8859_1))
                .as("the tenant's bytes travel verbatim")
                .contains("Feature: a\n")
                .startsWith("KAASSRC1")
                .endsWith("KAASEND1");
    }

    private static ExecutionLoop loopWithoutClient() {
        return loopWith(null);
    }

    /**
     * A launcher that only answers what runtime it instantiates.
     *
     * <p>The loop refuses to frame source unless the runtime can actually enforce the source filesystem, so
     * these tests need a launcher that says which one it is. It runs nothing: every case here is decided
     * before a container would exist, which is the point of deciding them there.
     */
    private static SandboxLauncher mediatedLauncher() {
        return new SandboxLauncher() {
            @Override
            public SandboxSecurityProfile profile() {
                return SandboxSecurityProfile.version1(PINNED_IMAGE, ExecutionRuntimeType.GVISOR);
            }

            @Override
            public SandboxOutcome run(SandboxLaunchRequest request) {
                throw new AssertionError("no case in this suite reaches a launch");
            }

            @Override
            public SandboxOutcome run(SandboxLaunchRequest request, ExecutionAuthority authority) {
                throw new AssertionError("no case in this suite reaches a launch");
            }

            @Override
            public SandboxLauncher withSource(SandboxSecurityProfile.SourceDelivery delivery) {
                throw new AssertionError("no case in this suite reaches a launch");
            }
        };
    }

    private static ExecutionLoop loopWith(ControlPlaneClient client) {
        JsonMapper mapper = JsonMapper.builder().build();
        return new ExecutionLoop(
                client,
                new CommandValidator(mapper),
                mediatedLauncher(),
                mapper,
                Clock.systemUTC(),
                com.kaas.runner.sandbox.SyntheticProbe.WORKLOAD_SOURCE_VERIFY,
                null,
                true);
    }

    private static SourceBundle bundle() {
        byte[] content = "Feature: a\n".getBytes(StandardCharsets.UTF_8);
        var expected = List.of(new SourceBundle.ExpectedEntry("features/a.feature", SourceBundle.sha256(content)));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("features/a.feature", content);
        return SourceBundle.verified(archiveOf(entries), expected, SourceBundle.bundleDigest(expected));
    }

    /** A command naming one feature. Only the source bundle matters here; the rest is shape. */
    private static ValidatedCommand command() {
        byte[] content = "Feature: a\n".getBytes(StandardCharsets.UTF_8);
        var feature = new ValidatedCommand.Feature(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "features/a.feature",
                SourceBundle.sha256(content));
        var expected = List.of(new SourceBundle.ExpectedEntry("features/a.feature", SourceBundle.sha256(content)));
        return new ValidatedCommand(
                UUID.randomUUID(),
                "sha256:" + "0".repeat(64),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                UUID.randomUUID(),
                1,
                1,
                "sha256:" + "1".repeat(64),
                Instant.now(),
                Instant.now().plusSeconds(600),
                "SYNTHETIC",
                "1",
                "DENY_ALL",
                "kaas.sandbox.v1",
                "DOCKER",
                List.of(),
                new ValidatedCommand.SourceBundleAuthorization(
                        SourceBundle.bundleDigest(expected), List.of(feature)));
    }

    private static byte[] archiveOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.setMethod(ZipOutputStream.STORED);
            for (var entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setSize(entry.getValue().length);
                zipEntry.setCompressedSize(entry.getValue().length);
                CRC32 crc = new CRC32();
                crc.update(entry.getValue());
                zipEntry.setCrc(crc.getValue());
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }
}
