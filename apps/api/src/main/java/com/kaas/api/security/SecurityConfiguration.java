package com.kaas.api.security;

import com.kaas.api.shared.ProblemSupport;
import com.kaas.api.shared.RequestSizeFilter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {

    /**
     * The internal service surface, on its own chain ahead of the tenant API.
     *
     * <p>It is separate because the two have different authentication shapes, not merely different paths. A
     * tenant token carries an {@code org_id} and acts for one organization; a platform service token carries no
     * tenancy at all, because a worker is not a tenant and every tenant scope it touches comes from the run it
     * names rather than from its own credentials. Mixing them into one chain would mean the tenant converter had
     * to tolerate a missing organization, which is exactly the hole that lets an unscoped token reach tenant
     * data.
     *
     * <p>These operations are deliberately absent from the public OpenAPI contract. They are not a private
     * corner of the tenant API; they are a different API with a different audience.
     */
    @Bean
    @Order(1)
    SecurityFilterChain internalServiceFilterChain(
            HttpSecurity http,
            ProblemSupport problems,
            @Value("${kaas.api.max-request-bytes}") long maxRequestBytes)
            throws Exception {
        return http.securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // The egress proxy gets its own authority and only its own.
                        //
                        // It is the one platform component deliberately reachable from an untrusted sandbox,
                        // which makes it the one whose credential is most likely to be taken. The general
                        // service authority can advance phases, submit results, and mint execution
                        // authorizations for any run in any tenant — so handing the proxy that authority
                        // would mean a proxy compromise could drive the control plane. It needs to ask one
                        // question, so it can ask one question.
                        .requestMatchers(EGRESS_PATH)
                        .hasAuthority(EGRESS_AUTHORITY)
                        .anyRequest()
                        .hasAuthority(SERVICE_AUTHORITY))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(SecurityConfiguration::serviceAuthentication))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            problems.write(
                                    request,
                                    response,
                                    HttpStatus.UNAUTHORIZED,
                                    "UNAUTHENTICATED",
                                    "A valid service bearer token is required.");
                        }))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, exception) ->
                        problems.write(
                                request,
                                response,
                                HttpStatus.FORBIDDEN,
                                "FORBIDDEN",
                                "The authenticated principal cannot perform this operation.")))
                .addFilterAfter(new RequestSizeFilter(maxRequestBytes, problems), BearerTokenAuthenticationFilter.class)
                .build();
    }

    static final String SERVICE_AUTHORITY = "ROLE_KAAS_SERVICE";

    /** What the egress proxy is allowed to do, which is exactly one thing. */
    static final String EGRESS_AUTHORITY = "ROLE_KAAS_EGRESS";

    /** The subject that receives {@link #EGRESS_AUTHORITY}, and the only one that does. */
    static final String EGRESS_SUBJECT = "kaas.egress-proxy";

    static final String EGRESS_PATH = "/internal/v1/egress/**";

    /**
     * Authenticates a platform service.
     *
     * <p>The subject must be in the reserved {@code kaas.} namespace — the same namespace tenant tokens are
     * refused for — so the two populations cannot overlap in either direction. A service token carries no
     * {@code org_id}, and one that does is refused rather than quietly accepted: a credential that is both a
     * service and a tenant is a confusion waiting to be exploited.
     */
    private static AbstractAuthenticationToken serviceAuthentication(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null
                || subject.isBlank()
                || subject.length() > 255
                || subject.codePoints().anyMatch(Character::isISOControl)
                || !subject.regionMatches(true, 0, RESERVED_ACTOR_PREFIX, 0, RESERVED_ACTOR_PREFIX.length())
                || jwt.getClaims().containsKey("org_id")
                || jwt.getExpiresAt() == null) {
            throw invalidClaims();
        }
        // One authority or the other, never both. Granting the proxy the service authority as well "so it
        // still works" would undo the whole point of separating them, and granting the general services the
        // egress authority would let any of them answer for the proxy.
        String authority = EGRESS_SUBJECT.equals(subject) ? EGRESS_AUTHORITY : SERVICE_AUTHORITY;
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority)), subject);
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ProblemSupport problems,
            @Value("${kaas.api.max-request-bytes}") long maxRequestBytes)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(SecurityConfiguration::authentication))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            problems.write(
                                    request,
                                    response,
                                    HttpStatus.UNAUTHORIZED,
                                    "UNAUTHENTICATED",
                                    "A valid bearer token is required.");
                        }))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, exception) ->
                        problems.write(
                                request,
                                response,
                                HttpStatus.FORBIDDEN,
                                "FORBIDDEN",
                                "The authenticated principal cannot perform this operation.")))
                .addFilterAfter(new RequestSizeFilter(maxRequestBytes, problems), BearerTokenAuthenticationFilter.class)
                .build();
    }

    /**
     * The {@code kaas.} prefix is reserved for the platform's own actors and is refused here, at the only place a
     * subject enters the system.
     *
     * <p>The subject becomes {@code test_runs.updated_by} and {@code run_lifecycle_events.actor}. Those are
     * audit evidence, and the schema treats some of these names as proof of authorship — a scheduling event is
     * only valid when its actor is {@code kaas.scheduler}, and a queue expiry only when it is
     * {@code kaas.queue-reaper}. Without this, any token whose subject a client can choose could record its own
     * actions as the platform's, and {@code created_by} is public in the run representation, so a tenant could
     * make a run read as platform-authored.
     */
    private static final String RESERVED_ACTOR_PREFIX = "kaas.";

    private static AbstractAuthenticationToken authentication(Jwt jwt) {
        String subject = jwt.getSubject();
        Object organizationClaim = jwt.getClaims().get("org_id");
        if (subject == null
                || subject.isBlank()
                || subject.length() > 255
                || subject.codePoints().anyMatch(Character::isISOControl)
                || subject.regionMatches(true, 0, RESERVED_ACTOR_PREFIX, 0, RESERVED_ACTOR_PREFIX.length())
                || !(organizationClaim instanceof String value)
                || jwt.getExpiresAt() == null) {
            throw invalidClaims();
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value.toLowerCase(Locale.ROOT))) {
                throw invalidClaims();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidClaims();
        }
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")), subject);
    }

    private static OAuth2AuthenticationException invalidClaims() {
        return new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token"), "The token is missing required trusted claims.");
    }
}
