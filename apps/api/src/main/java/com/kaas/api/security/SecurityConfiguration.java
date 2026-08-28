package com.kaas.api.security;

import com.kaas.api.shared.ProblemSupport;
import com.kaas.api.shared.RequestSizeFilter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    @Bean
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

    private static AbstractAuthenticationToken authentication(Jwt jwt) {
        String subject = jwt.getSubject();
        Object organizationClaim = jwt.getClaims().get("org_id");
        if (subject == null
                || subject.isBlank()
                || subject.length() > 255
                || subject.codePoints().anyMatch(Character::isISOControl)
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
