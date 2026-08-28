package com.kaas.api.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class TenantPrincipalResolver {
    public TenantPrincipal resolve(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("A trusted JWT authentication is required");
        }
        return new TenantPrincipal(token.getToken().getSubject(), UUID.fromString(token.getToken().getClaimAsString("org_id")));
    }
}
