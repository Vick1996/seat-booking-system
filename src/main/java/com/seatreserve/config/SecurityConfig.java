package com.seatreserve.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Stateless bearer-JWT security. We mint tokens ourselves (POST /auth/token) and Spring Security
 * verifies them on every request. Two kinds of caller matter to the API: anonymous (public routes),
 * authenticated users, and admins (the "admin": true claim becomes ROLE_ADMIN).
 */
@Configuration
@EnableMethodSecurity // turns on @PreAuthorize (used on ShowController.create)
public class SecurityConfig {
    /** Pinned on both the signing and the verifying side, so the algorithm can never drift. */
    public static final MacAlgorithm JWT_ALG = MacAlgorithm.HS256;

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
                // CSRF abuses cookies the browser attaches by itself. A bearer token is attached by
                // the client's own code, so a stateless token API has nothing for CSRF to ride on.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a // first match wins, so order matters
                        // /error: without it a container-level error is itself rejected with a 401
                        .requestMatchers("/auth/**", "/actuator/**", "/error").permitAll()
                        .requestMatchers("/healthz", "/readyz", "/metrics", "/ops/logs").permitAll() // see OpsController
                        .requestMatchers(HttpMethod.GET, "/shows/*").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(SecurityConfig::unauthorized)
                        .accessDeniedHandler(SecurityConfig::forbidden)
                        .jwt(j -> j.jwtAuthenticationConverter(adminClaimToRole())))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(SecurityConfig::unauthorized)
                        .accessDeniedHandler(SecurityConfig::forbidden))
                .addFilterAfter(new UserLogFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(SeatProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(props)).macAlgorithm(JWT_ALG).build();
        // Spring's default tolerates 60s of clock skew, i.e. accepts a token that expired a minute ago.
        OAuth2TokenValidator<Jwt> notExpired = new JwtTimestampValidator(Duration.ZERO);
        // a token with no subject has no identity to act as
        OAuth2TokenValidator<Jwt> hasSubject = jwt ->
                jwt.getSubject() == null || jwt.getSubject().isBlank()
                        ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "subject is required", null))
                        : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(notExpired, hasSubject));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SeatProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key(props)));
    }

    private static SecretKey key(SeatProperties props) {
        return new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Our "admin": true claim becomes Spring's ROLE_ADMIN authority. */
    private static JwtAuthenticationConverter adminClaimToRole() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> Boolean.TRUE.equals(jwt.getClaim("admin"))
                ? List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.<GrantedAuthority>of());
        return converter;
    }

    // Spring Security's own 401/403 have empty bodies; keep the API's documented JSON error shape.
    private static void unauthorized(HttpServletRequest req, HttpServletResponse res, AuthenticationException e)
            throws IOException {
        res.setStatus(401);
        res.setHeader("WWW-Authenticate", "Bearer");
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"unauthorized\",\"reason\":\"missing-or-invalid-token\"}");
    }

    private static void forbidden(HttpServletRequest req, HttpServletResponse res, AccessDeniedException e)
            throws IOException {
        res.setStatus(403);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"Forbidden\",\"reason\":\"forbidden\",\"message\":\"you are not allowed to do this\"}");
    }

    /**
     * Puts the authenticated user id on every log line. It cannot be done in RequestIdFilter: that
     * filter wraps Spring Security's, and the security context is already cleared when it logs. Created
     * with `new`, not as a @Bean, because a filter bean would also be registered globally by Boot.
     */
    static final class UserLogFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken))
                MDC.put("user_id", a.getName());
            chain.doFilter(req, res);
        }
    }
}
