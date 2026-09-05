package com.kaas.egress;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks the control plane, over HTTP, whether one capability may reach one destination now.
 *
 * <h2>What is sent, and what is deliberately not</h2>
 *
 * <p>The request carries the opaque capability and the canonical destination. It does not carry a run
 * identifier, an attempt, an epoch, or a policy revision — not because they are secret, but because the proxy
 * must not be the thing that asserts them. Everything about who this capability belongs to is looked up from
 * the credential by the side that owns the state; a proxy that named its own run would be a proxy that could
 * name a different one.
 *
 * <p>The response carries a verdict and a reason. Nothing else: no policy contents, no worker identity, no
 * expiry. The proxy sits on a network an untrusted sandbox can reach, so every field it never receives is a
 * field a proxy compromise cannot disclose.
 *
 * <h2>Failure is denial</h2>
 *
 * <p>A timeout, a connection failure, a 5xx, an unparseable body, or an unrecognised verdict all produce
 * {@link DenialReason#AUTHORIZATION_UNAVAILABLE}. There is no retry and no cached previous answer. Retrying
 * would spend a tunnel's revalidation budget asking the same question, and a cached answer is a decision from a
 * moment that has passed — which is precisely what assignment-scoped authority is not allowed to rely on.
 */
public final class ControlPlaneAuthorizer implements EgressAuthorizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;

    private final URI endpoint;

    private final String serviceAuthorization;

    private final Duration timeout;

    public ControlPlaneAuthorizer(URI controlPlane, String serviceAuthorization, Duration timeout) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                // The proxy talks to one known service. Following a redirect would mean sending the platform's
                // own service credential wherever that service was told to point.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.endpoint = controlPlane.resolve("/internal/v1/egress/authorizations");
        this.serviceAuthorization = serviceAuthorization;
        this.timeout = timeout;
    }

    @Override
    public AuthorizationDecision authorize(String capabilityToken, CanonicalDestination destination) {
        String body = JSON.writeValueAsString(java.util.Map.of(
                "capabilityToken", capabilityToken,
                "host", destination.host(),
                "port", destination.port(),
                "scheme", destination.scheme().name()));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", serviceAuthorization)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AuthorizationDecision.denied(DenialReason.AUTHORIZATION_UNAVAILABLE);
        } catch (Exception unreachable) {
            // Deliberately catching everything. Any exception at all means the answer was not obtained, and
            // the only safe reading of "no answer" is no.
            return AuthorizationDecision.denied(DenialReason.AUTHORIZATION_UNAVAILABLE);
        }
        if (response.statusCode() != 200) {
            return AuthorizationDecision.denied(DenialReason.AUTHORIZATION_UNAVAILABLE);
        }

        JsonNode decision;
        try {
            decision = JSON.readTree(response.body());
        } catch (RuntimeException unreadable) {
            return AuthorizationDecision.denied(DenialReason.AUTHORIZATION_UNAVAILABLE);
        }
        JsonNode verdict = decision.get("decision");
        if (verdict == null || !verdict.isString()) {
            return AuthorizationDecision.denied(DenialReason.AUTHORIZATION_UNAVAILABLE);
        }
        if (!"AUTHORIZED".equals(verdict.asString())) {
            return AuthorizationDecision.denied(reasonOf(decision));
        }
        return AuthorizationDecision.granted();
    }

    /**
     * The named reason, or a generic denial when the control plane names one this proxy does not know.
     *
     * <p>An unknown reason is still a denial. Treating it as anything else would mean a control plane that
     * gained a new refusal category could have that category read as permission by an older proxy.
     */
    private static DenialReason reasonOf(JsonNode decision) {
        JsonNode reason = decision.get("reason");
        if (reason == null || !reason.isString()) {
            return DenialReason.DESTINATION_NOT_ALLOWED;
        }
        for (DenialReason candidate : DenialReason.values()) {
            if (candidate.name().equals(reason.asString())) {
                return candidate;
            }
        }
        return DenialReason.DESTINATION_NOT_ALLOWED;
    }
}
