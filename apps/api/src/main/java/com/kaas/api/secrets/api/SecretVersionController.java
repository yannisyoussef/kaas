package com.kaas.api.secrets.api;

import com.kaas.api.secrets.application.SecretVersionService;
import com.kaas.api.secrets.domain.SecretLimits;
import com.kaas.api.secrets.domain.SecretVersion;
import com.kaas.api.security.TenantPrincipalResolver;
import com.kaas.api.shared.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's secret-version surface: write a new version, list versions, revoke one.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>There is no endpoint that returns a value, a ciphertext, a Transit context, or anything derived from a
 * value — not for the current version, not for a history, not for an administrator. A secret goes in here and
 * comes out only inside an authorized execution, through a capability the platform issued for that execution.
 *
 * <h2>Why the body is raw bytes</h2>
 *
 * <p>{@code application/octet-stream}, read straight from the request with a hard bound, rather than a JSON field.
 * A JSON string would be decoded by the message converter into an immutable {@code String} before this code saw
 * it — a copy nothing can clear — and would impose an escaping layer through which a PEM block's exact bytes
 * would have to survive. The bytes posted are the bytes stored, and they are cleared as soon as Transit has them.
 */
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/secret-references/{secretReferenceId}/versions")
public class SecretVersionController {
    private final SecretVersionService service;
    private final TenantPrincipalResolver principals;

    public SecretVersionController(SecretVersionService service, TenantPrincipalResolver principals) {
        this.service = service;
        this.principals = principals;
    }

    @PostMapping
    ResponseEntity<VersionView> create(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID secretReferenceId,
            HttpServletRequest request) {
        var principal = principals.resolve(authentication);
        if (!MediaType.APPLICATION_OCTET_STREAM_VALUE.equalsIgnoreCase(baseType(request.getContentType()))) {
            throw ApiException.validation(
                    "/", "A secret value is posted as application/octet-stream, exactly the bytes to store.");
        }
        byte[] value = readBounded(request);
        SecretVersion created = service.createVersion(principal, projectId, secretReferenceId, value);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/secret-references/"
                        + secretReferenceId + "/versions/" + created.version()))
                .cacheControl(CacheControl.noStore())
                .body(VersionView.of(created));
    }

    @GetMapping
    ResponseEntity<List<VersionView>> list(
            Authentication authentication, @PathVariable UUID projectId, @PathVariable UUID secretReferenceId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listVersions(principals.resolve(authentication), projectId, secretReferenceId)
                        .stream()
                        .map(VersionView::of)
                        .toList());
    }

    /**
     * Revokes one version. A POST to a sub-resource rather than a DELETE, because nothing is deleted from the
     * caller's point of view: the version remains listed, marked revoked, forever.
     */
    @PostMapping("/{version}/revocation")
    ResponseEntity<VersionView> revoke(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID secretReferenceId,
            @PathVariable @Min(1) @Max(100000) int version) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(VersionView.of(service.revokeVersion(
                        principals.resolve(authentication), projectId, secretReferenceId, version)));
    }

    /**
     * Reads at most one byte more than the bound, so an oversized body is refused without being buffered whole.
     */
    private static byte[] readBounded(HttpServletRequest request) {
        if (request.getContentLengthLong() > SecretLimits.MAX_VALUE_BYTES) {
            throw ApiException.validation("/", "A secret value may be at most 8192 bytes.");
        }
        byte[] read;
        try (InputStream body = request.getInputStream()) {
            read = body.readNBytes(SecretLimits.MAX_VALUE_BYTES + 1);
        } catch (IOException unreadable) {
            throw ApiException.validation("/", "The secret value could not be read.");
        }
        if (read.length > SecretLimits.MAX_VALUE_BYTES) {
            Arrays.fill(read, (byte) 0);
            throw ApiException.validation("/", "A secret value may be at most 8192 bytes.");
        }
        return read;
    }

    private static String baseType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameters = contentType.indexOf(';');
        return (parameters < 0 ? contentType : contentType.substring(0, parameters)).strip();
    }

    /** Metadata only. There is no field a value, a ciphertext, a length or a digest could be put in. */
    record VersionView(UUID secretReferenceId, int version, String createdBy, Instant createdAt, boolean revoked,
            Instant revokedAt) {
        static VersionView of(SecretVersion version) {
            return new VersionView(version.secretReferenceId(), version.version(), version.createdBy(),
                    version.createdAt(), version.revoked(), version.revokedAt());
        }
    }
}
