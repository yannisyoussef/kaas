package com.kaas.api.execution.application;

import com.kaas.api.execution.domain.SandboxSecurityAttestation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supplies the deployment's sandbox security assessment, or nothing.
 *
 * <p>Nothing is the default, and it is the correct production state today. The hostile-execution gate runs in
 * CI against a real daemon and produces an assessment; nothing yet transports that assessment into a running
 * control plane automatically. Until something does, this returns empty and every authorization fails with
 * {@code SECURITY_GATE_UNAVAILABLE}, which is the honest outcome: the platform cannot demonstrate that this
 * host's sandbox enforces what it requires, so it does not pretend to.
 *
 * <p>The document arrives as deployment configuration. That places it in the same trust domain as the database
 * credentials and the JWT issuer — the operator's — and emphatically not in the tenant's or the worker's. There
 * is deliberately no endpoint that accepts one, so nothing that authenticates to this service can assert its own
 * security posture.
 *
 * <p>Parsing is strict. An unknown property is a refusal rather than a silent discard, because an assessment
 * with a misspelled control name would otherwise be an assessment missing that control while looking complete.
 */
@Component
public class SandboxSecurityAttestationSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxSecurityAttestationSource.class);

    /**
     * A private mapper, so a change to a shared one cannot reach the parser whose input decides whether
     * execution is permitted.
     *
     * <p><strong>The strictness that matters is NOT these flags.</strong> This class reads the document into a
     * {@link tools.jackson.databind.JsonNode} rather than binding it to a type, and
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} applies only to bean binding — it does nothing here. An earlier
     * comment claimed otherwise, which was worse than saying nothing: it described a protection that was not
     * operating, in the one place where an undefined field must never be silently discarded.
     *
     * <p>The real enforcement is the explicit property allowlist below, and it is the only enforcement. It is
     * covered by {@code WhenAnOtherwiseCompleteDocumentCarriesAnUnknownProperty}, which exists because mutation
     * testing showed nothing else covers it — and which was once deleted without any test going red.
     */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final Optional<SandboxSecurityAttestation> attestation;
    private final String parseFailure;

    public SandboxSecurityAttestationSource(
            @Value("${kaas.execution.sandbox-attestation:}") String document) {
        if (document == null || document.isBlank()) {
            this.attestation = Optional.empty();
            this.parseFailure = null;
            LOGGER.atInfo()
                    .addKeyValue("event", "SANDBOX_ATTESTATION_ABSENT")
                    .log("No sandbox security assessment is configured; execution authorization is unavailable");
            return;
        }
        SandboxSecurityAttestation parsed = null;
        String failure = null;
        try {
            parsed = parse(document);
        } catch (RuntimeException malformed) {
            // The message is kept out of the log deliberately: a malformed attestation is operator-supplied
            // configuration, and echoing it risks putting whatever was in that property into a log line.
            failure = "the configured sandbox attestation could not be parsed";
        }
        this.attestation = Optional.ofNullable(parsed);
        this.parseFailure = failure;
        if (failure != null) {
            LOGGER.atError()
                    .addKeyValue("event", "SANDBOX_ATTESTATION_UNPARSEABLE")
                    .log("A sandbox security assessment is configured but could not be read; execution is refused");
        }
    }

    /** The attestation, if one is configured and could be read. Never a partially-parsed one. */
    public Optional<SandboxSecurityAttestation> attestation() {
        return attestation;
    }

    /** Why there is no usable attestation, when there is none. */
    public Optional<String> unavailableReason() {
        if (attestation.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(parseFailure != null ? parseFailure : "no sandbox security assessment is configured");
    }

    private static SandboxSecurityAttestation parse(String document) {
        JsonNode root = STRICT.readTree(document);
        if (!root.isObject()) {
            throw new IllegalArgumentException("An attestation is a JSON object.");
        }
        for (String property : root.propertyNames()) {
            if (!java.util.Set.of(
                            "schemaVersion",
                            "securityProfileVersion",
                            "probeImageDigest",
                            "runtime",
                            "assessedAt",
                            "mandatoryControls",
                            "egressControls",
                            "digest")
                    .contains(property)) {
                throw new IllegalArgumentException("An attestation carries no unknown properties.");
            }
        }
        JsonNode controls = root.get("mandatoryControls");
        if (controls == null || !controls.isObject()) {
            throw new IllegalArgumentException("An attestation enumerates its mandatory controls.");
        }
        Map<String, String> verdicts = verdictsOf(controls);
        // Absent is legal and means "this deployment makes no claim about egress", which the ALLOWLIST path
        // reads as a refusal. Present but malformed is an error rather than an absence: a document that tried
        // to say something about egress and failed must not be read as one that said nothing.
        JsonNode egress = root.get("egressControls");
        Map<String, String> egressVerdicts;
        if (egress == null || egress.isNull()) {
            egressVerdicts = Map.of();
        } else if (!egress.isObject()) {
            throw new IllegalArgumentException("Egress controls are an object when present.");
        } else {
            egressVerdicts = verdictsOf(egress);
        }
        return new SandboxSecurityAttestation(
                text(root, "schemaVersion"),
                text(root, "securityProfileVersion"),
                text(root, "probeImageDigest"),
                text(root, "runtime"),
                Instant.parse(text(root, "assessedAt")),
                verdicts,
                egressVerdicts,
                text(root, "digest"));
    }

    private static Map<String, String> verdictsOf(JsonNode controls) {
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (String control : controls.propertyNames()) {
            JsonNode verdict = controls.get(control);
            if (!verdict.isString()) {
                throw new IllegalArgumentException("Each control carries a textual verdict.");
            }
            verdicts.put(control, verdict.stringValue());
        }
        return verdicts;
    }

    private static String text(JsonNode root, String property) {
        JsonNode value = root.get(property);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("An attestation property is required and textual.");
        }
        return value.stringValue();
    }
}
