package ai.myrmec.engine._system.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
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
                        // Health check
                        .requestMatchers("/actuator/health").permitAll()
                        // OpenAPI documentation
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Agent endpoints - require AGENT role
                        .requestMatchers("/api/v1/agent/**").hasRole("AGENT")
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
