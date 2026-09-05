package com.kaas.api.internal;

import com.kaas.api.execution.application.ExecutionAuthorizationService;
import com.kaas.api.execution.application.SourceCapabilityService;
import com.kaas.api.execution.domain.ExecutionDenial;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal execution-authority surface. Not a tenant API, and deliberately absent from the public OpenAPI
 * document: its audience is the platform's own workers.
 *
 * <p><strong>What a caller may say.</strong> Almost nothing. A worker names the run, the attempt, and the
 * assignment epoch it believes it holds — the epoch has to come from the caller, because it is the caller
 * asserting which assignment it thinks it is — and every one of those is checked against authoritative state
 * rather than trusted. Everything else that could matter is refused as input by construction: there is no field
 * for an organization, a worker identity, a security verdict, a network policy, an engine version, an image, a
 * command line, or a sandbox flag. A request cannot widen its own authority because there is nowhere to write it.
 *
 * <p>Worker identity comes from the authenticated service principal on the internal filter chain, which is the
 * same mechanism and the same strength as the heartbeat. It is not mTLS, and this comment says so rather than
 * implying a stronger boundary than the deployment actually has.
 *
 * <p><strong>Nothing here executes anything.</strong> A successful response hands back a command document that
 * has nowhere to go: it is not published, not dispatched, and not reachable from the sandbox launcher. The run
 * stays in {@code CLAIMED}.
 */
@Validated
@RestController
@RequestMapping("/internal/v1")
class ExecutionAuthorizationController {

    /**
     * The header a capability token is presented in.
     *
     * <p>Not the query string, where it would land in every access log and proxy trace; not the body, where a
     * request replay would carry it; and not {@code Authorization}, which already carries the worker's own
     * service credential and must keep doing so, because redemption revalidates worker identity as well as the
     * capability. Two credentials with two jobs need two places to live.
     */
    private static final String SOURCE_CAPABILITY_HEADER = "X-KaaS-Source-Capability";

    private final ExecutionAuthorizationService authorizations;
    private final SourceCapabilityService sources;

    ExecutionAuthorizationController(
            ExecutionAuthorizationService authorizations, SourceCapabilityService sources) {
        this.authorizations = authorizations;
        this.sources = sources;
    }

    /**
     * Authorizes one assignment and returns its command with fresh capability material.
     *
     * <p>A refusal is 409 rather than 403 or 404. The caller is the platform, so there is no tenant to conceal
     * the run's existence from, and the honest statement is that the state the caller believes in is not the
     * state that exists.
     */
    @PostMapping("/runs/{runId}/attempts/{attemptId}/execution-authorizations")
    ResponseEntity<Map<String, Object>> authorize(
            Authentication authentication,
            @PathVariable UUID runId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody AuthorizationRequest request) {
        var outcome = authorizations.authorize(
                runId, attemptId, request.assignmentEpoch(), authentication.getName());
        if (outcome.denial().isPresent()) {
            return refusal(outcome.denial().orElseThrow());
        }
        var delivery = outcome.delivery().orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authorizationId", delivery.authorization().authorizationId().toString());
        body.put("expiresAt", delivery.authorization().expiresAt().toString());
        body.put("commandId", delivery.commandId().toString());
        body.put("commandDigest", delivery.commandDigest());
        body.put("commandExpiresAt", delivery.commandExpiresAt().toString());
        // Capability identity lives here rather than in the command, because it rotates on every delivery. The
        // command is the artifact; this envelope is what is true for this response only.
        body.put("sourceCapabilityId", delivery.sourceCapabilityId().toString());
        // The bearer token exists in this response and nowhere else. It was never written to a database, a log,
        // a metric, or the persisted command, and the server cannot produce it again.
        body.put("sourceCapabilityToken", delivery.sourceCapabilityToken());
        body.put("secretCapabilityTokens", delivery.secretCapabilityTokens());
        // Present only for a policy that needs one, and absent rather than null for one that does not: a
        // DENY_ALL sandbox has nothing to present a credential to, and emitting an empty field would invite a
        // worker to pass something along anyway.
        //
        // Like the others, this token exists in this response and nowhere else. It is deliberately absent
        // from the command document, because the command is immutable and digested while this rotates on
        // every delivery — a field the digest cannot cover must not be emitted inside it.
        delivery.egressCapabilityToken().ifPresent(token -> body.put("egressCapabilityToken", token));
        // The destinations the pinned policy permits, present only alongside a credential that could use
        // them. This is launch material for the worker's platform-owned workload, not authority: the proxy
        // asks this control plane about every destination on every request, so nothing a worker does with
        // this list widens what it may reach. It is deliberately outside the command for the same reason the
        // token is — the command is immutable and digested, and a second copy of the policy inside it would
        // be a field nothing enforces from.
        if (!delivery.egressDestinations().isEmpty()) {
            body.put("egressDestinations", delivery.egressDestinations().stream()
                    .map(destination -> Map.<String, Object>of(
                            "host", destination.host(),
                            "port", destination.port(),
                            "scheme", destination.scheme().name()))
                    .toList());
        }
        // The parsed document, not its serialization. Putting the string here produced a double-encoded field a
        // consumer had to unwrap before it could validate against the contract — which is the mildest form of
        // the "verified a projection, transmitted different bytes" trap, and one nobody would notice until they
        // tried to verify.
        body.put("command", delivery.commandDocument());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    /**
     * Exchanges a source capability for the pinned sources it authorizes.
     *
     * <p>A POST rather than a GET, because it is not idempotent in the sense that matters: it consumes one of a
     * bounded number of redemptions, and it must never be cached by anything between the worker and here.
     */
    @PostMapping(value = "/source-bundles", produces = "application/zip")
    ResponseEntity<byte[]> sourceBundle(
            Authentication authentication,
            @RequestHeader(name = SOURCE_CAPABILITY_HEADER, required = false) String capabilityToken) {
        var redemption = sources.redeem(capabilityToken, authentication.getName());
        if (redemption.denial().isPresent()) {
            return ResponseEntity.status(statusOf(redemption.denial().orElseThrow()))
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        var bundle = redemption.bundle().orElseThrow();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                // The semantic digest of the contents, so a worker can verify what it received without the
                // server having to promise byte-identical framing across runtimes.
                .header("X-KaaS-Bundle-Digest", bundle.contentDigest())
                .body(bundle.archive());
    }

    private ResponseEntity<Map<String, Object>> refusal(ExecutionDenial denial) {
        return ResponseEntity.status(statusOf(denial))
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", disclosable(denial).name()));
    }

    /**
     * What a refusal may say out loud.
     *
     * <p>{@code ASSIGNMENT_STALE} is returned only when a run IS currently claimed and the caller named the
     * wrong attempt, epoch, or worker; every other state answers {@code EXECUTION_NOT_AUTHORIZED}. Distinguishing
     * the two in the response therefore confirms which run and attempt identifiers are live — and the internal
     * chain grants one authority to every platform service, untenanted, so any service credential could use it
     * as a cross-tenant confirmation oracle. The distinction is real and useful, so it is kept in the log and
     * the metric where an operator can see it and a caller cannot.
     */
    private static ExecutionDenial disclosable(ExecutionDenial denial) {
        return denial == ExecutionDenial.ASSIGNMENT_STALE ? ExecutionDenial.EXECUTION_NOT_AUTHORIZED : denial;
    }

    /**
     * Every refusal is 409.
     *
     * <p>One status for all of them on purpose. Distinguishing "expired" from "fenced" from "does not exist" by
     * status code would turn this endpoint into an oracle a holder of one capability could use to learn about
     * others. The body names the category, which is what a legitimate worker needs to decide whether to retry.
     */
    private static int statusOf(ExecutionDenial denial) {
        return 409;
    }

    /**
     * The assignment epoch the caller believes it holds.
     *
     * <p>The only thing a caller supplies, and it is a claim rather than an authority: it is compared against the
     * live assignment, so naming a different epoch fails rather than succeeding as somebody else.
     */
    record AuthorizationRequest(@NotNull @Min(1) @Max(1000) Integer assignmentEpoch) {}
}
