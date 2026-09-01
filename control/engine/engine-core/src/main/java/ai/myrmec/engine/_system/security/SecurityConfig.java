package ai.myrmec.engine._system.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Internal chain (HS256 Myrmec JWTs). Ordered after the External-API chain
     * (see {@link ExternalApiSecurityConfig}) since it has no security matcher
     * and would otherwise greedily claim {@code /api/v1/external/**}.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Allow frames for H2 console
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        // H2 console (dev/e2e only)
                        .requestMatchers("/h2-console/**").permitAll()
                        // Public auth endpoints - no auth required
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/api/v1/auth/providers/enabled").permitAll()
                        .requestMatchers("/api/v1/auth/external/**").permitAll()
                        .requestMatchers("/api/v1/auth/oidc/**").permitAll()
                        // Agent auth endpoints (public - before agent role check)
                        .requestMatchers("/api/v1/agent/auth/**").permitAll()
                        // WebSocket endpoint - auth handled by handshake interceptor
                        .requestMatchers("/api/v1/agent/ws").permitAll()
                        // Slice 4c — conversation-scoped agent socket; auth is
                        // performed in the WS handshake interceptor (agent JWT).
                        .requestMatchers("/api/v1/agent/conversation").permitAll()
                        // User-facing conversation stream (SSE) - token arrives as a
                        // ?token= query param (EventSource can't set headers) and is
                        // validated inside ConversationStreamController.
                        .requestMatchers("/api/v1/conversations/*/stream").permitAll()
                        // Health check
                        .requestMatchers("/actuator/health").permitAll()
                        // License tier info for E2E harness (read-only, no secrets)
                        .requestMatchers("/actuator/info").permitAll()
                        // OpenAPI documentation
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Node-to-node mesh relay - guarded by a shared secret in the
                        // controller, not by a principal (peer replica, not a user/agent).
                        .requestMatchers("/api/v1/internal/**").permitAll()
                        // Knowledge source webhooks (#25a) - authenticated by an HMAC
                        // signature over the body inside KnowledgeWebhookService, not a JWT.
                        .requestMatchers("/api/v1/knowledge/webhooks/**").permitAll()
                        // Agent endpoints - require AGENT role
                        .requestMatchers("/api/v1/agent/**").hasRole("AGENT")
                        // Quota/budget admin endpoints - authenticated users are allowed through;
                        // QuotaAdminController enforces scope-aware BUDGET_OWNER/PLATFORM_ADMIN
                        // authorization via BudgetAuthorization.
                        .requestMatchers("/api/v1/admin/quotas/**").authenticated()
                        // Budget dashboard endpoints - authenticated users are allowed through;
                        // BudgetController enforces scope-aware view authorization.
                        .requestMatchers("/api/v1/budgets/**").authenticated()
                        // Admin endpoints - PLATFORM_ADMIN (tech) or ORG_ADMIN (governance).
                        // Individual controllers tighten further with @PreAuthorize.
                        .requestMatchers("/api/v1/admin/**").hasAnyRole("PLATFORM_ADMIN", "ORG_ADMIN")
                        // All other API endpoints require authentication
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
