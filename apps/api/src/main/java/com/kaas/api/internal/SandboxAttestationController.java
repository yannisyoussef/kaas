package com.kaas.api.internal;

import com.kaas.api.execution.application.AttestationSubmissionService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a runner delivers a freshly signed sandbox security assessment.
 *
 * <p>The body is the signed document exactly as the producer wrote it, taken as a string and handed to the
 * execution package untouched: this controller parses nothing, verifies nothing, and cannot reach the verifier,
 * the trust store or a verified attestation (the architecture test forbids it). It cannot nominate a key.
 */
@RestController
@RequestMapping("/internal/v1")
class SandboxAttestationController {
    private final AttestationSubmissionService submissions;

    SandboxAttestationController(AttestationSubmissionService submissions) {
        this.submissions = submissions;
    }

    @PostMapping(path = "/sandbox-attestations", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> submit(Authentication authentication, @RequestBody String document) {
        var outcome = submissions.submit(authentication.getName(), document);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", outcome.code());
        if (outcome.accepted()) {
            body.put("attestationId", outcome.attestationId());
            body.put("assessedAt", outcome.assessedAt().toString());
            body.put("usableUntil", outcome.usableUntil().toString());
            return ResponseEntity.status(outcome.stored() ? 201 : 200)
                    .cacheControl(CacheControl.noStore())
                    .body(body);
        }
        int status = "NOT_A_WORKER".equals(outcome.code()) ? 403 : 422;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
