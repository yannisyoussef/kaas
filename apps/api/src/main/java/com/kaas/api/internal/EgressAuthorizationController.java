package com.kaas.api.internal;

import com.kaas.api.execution.application.EgressAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one question the trusted egress proxy may ask.
 *
 * <h2>Why this is a separate controller with a separate authority</h2>
 *
 * <p>The proxy sits on a network an untrusted sandbox can reach, which makes its credential the most likely
 * one in the system to be taken. Every other operation under {@code /internal} can drive an execution:
 * advance a phase, submit a result, mint an authorization. This endpoint answers yes or no about one
 * destination, and the security chain grants the proxy's subject an authority that reaches nothing else — so
 * a stolen proxy credential buys the ability to ask this question and nothing more.
 *
 * <h2>What the answer deliberately omits</h2>
 *
 * <p>A verdict and a category. No run identifier, no attempt, no epoch, no worker, no policy contents, no
 * expiry instant, no resolved address. The proxy does not need any of them to decide what to do, and a field
 * it never receives is a field a proxy compromise cannot disclose.
 *
 * <p>Note what this endpoint does <em>not</em> do: it does not resolve the destination. Name resolution and
 * address classification belong to the proxy, which is the component that will open the socket — moving them
 * here would put a gap between the address that was checked and the address that gets connected to, which is
 * precisely the rebinding hole the whole design exists to close.
 */
@RestController
@RequestMapping("/internal/v1/egress")
class EgressAuthorizationController {

    private final EgressAuthorizationService egress;

    EgressAuthorizationController(EgressAuthorizationService egress) {
        this.egress = egress;
    }

    /**
     * Decides whether one capability may reach one destination, right now.
     *
     * <p>Always 200, whatever the verdict. Distinguishing a denial by status code would make this an oracle:
     * a holder of one capability could learn from the status alone whether another exists, whether a run is
     * still live, or whether a destination is in some policy. The body names the category, which is what the
     * proxy needs in order to answer its own client truthfully.
     *
     * <p>A POST rather than a GET because the capability must never appear in a URL, where it would reach an
     * access log, a proxy log, and a browser history.
     */
    @PostMapping("/authorizations")
    ResponseEntity<Map<String, Object>> authorize(@Valid @RequestBody DecisionRequest request) {
        EgressAuthorizationService.Decision decision =
                egress.authorize(request.capabilityToken(), request.host(), request.port(), request.scheme());

        Map<String, Object> body = new LinkedHashMap<>();
        if (decision == EgressAuthorizationService.Decision.AUTHORIZED) {
            body.put("decision", "AUTHORIZED");
        } else {
            body.put("decision", "DENIED");
            body.put("reason", reasonFor(decision));
        }
        return ResponseEntity.ok()
                // Never cached, by anything, anywhere. A cached authorization is a decision from a moment that
                // has passed, and the entire model rests on the moment being what changes.
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    /**
     * The proxy's own vocabulary, which is deliberately not this service's enum names.
     *
     * <p>They are two different vocabularies that happen to overlap, and mapping between them explicitly is
     * what stops a rename on either side silently becoming a reason the other does not recognise — which the
     * proxy would treat as a generic denial, turning a specific refusal into a vague one at exactly the
     * moment somebody is trying to understand an incident.
     */
    private static String reasonFor(EgressAuthorizationService.Decision decision) {
        return switch (decision) {
            case DENIED_POLICY -> "DESTINATION_NOT_ALLOWED";
            case DENIED_FENCED -> "ASSIGNMENT_FENCED";
            case DENIED_EXPIRED -> "CAPABILITY_EXPIRED";
            case DENIED_CAPABILITY -> "CAPABILITY_INVALID";
            case DENIED_STATE -> "RUN_NOT_EXECUTING";
            case AUTHORIZED -> throw new IllegalStateException("An authorization is not a denial.");
        };
    }

    /**
     * What the proxy sends.
     *
     * <p>The destination fields are bounded here as well as canonicalized in the service, because this is the
     * outer edge and an unbounded string reaching a parser is a cost the caller controls. The capability is
     * bounded too: a token is fixed-width server-generated material, so anything much longer is not a token.
     */
    record DecisionRequest(
            @NotBlank @Size(max = 128) String capabilityToken,
            @NotBlank @Size(max = 253) String host,
            @NotNull @Min(1) @Max(65535) Integer port,
            @NotBlank @Pattern(regexp = "HTTP|HTTPS") String scheme) {}
}
