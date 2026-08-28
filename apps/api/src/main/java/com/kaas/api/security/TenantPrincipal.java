package com.kaas.api.security;

import java.util.UUID;

public record TenantPrincipal(String principalId, UUID organizationId) {}
