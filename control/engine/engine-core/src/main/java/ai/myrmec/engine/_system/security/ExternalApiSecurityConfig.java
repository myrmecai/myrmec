package ai.myrmec.engine._system.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * External-API security ({@code /api/v1/external/**}) for #95 — Conversational
 * Services. Callers present a Keycloak client-credentials bearer token; this
 * chain validates it (RS256 / JWKS, issuer, expiry) and resolves the token's
 * client id to an active {@link ai.myrmec.engine.serviceaccount.ServiceAccount}
 * via {@link ServiceAccountJwtAuthenticationConverter}.
 *
 * <p>The whole chain is gated behind {@code myrmec.external-api.enabled=true} so
 * dev/test/internal-only deployments boot without a live Keycloak. When enabled
 * the operator must supply either {@code myrmec.external-api.keycloak.issuer-uri}
 * (preferred — enables OIDC discovery + issuer validation) or
 * {@code ...jwk-set-uri}. This chain is {@link Order @Order(1)}, ahead of the
 * internal HS256 chain which has no security matcher.</p>
 */
@Configuration
@ConditionalOnProperty(name = "myrmec.external-api.enabled", havingValue = "true")
public class ExternalApiSecurityConfig {

    @Value("${myrmec.external-api.keycloak.issuer-uri:}")
    private String issuerUri;

    @Value("${myrmec.external-api.keycloak.jwk-set-uri:}")
    private String jwkSetUri;

    @Bean
    public JwtDecoder externalApiJwtDecoder() {
        if (StringUtils.hasText(issuerUri)) {
            // OIDC discovery: validates signature, issuer and expiry.
            return JwtDecoders.fromIssuerLocation(issuerUri);
        }
        if (StringUtils.hasText(jwkSetUri)) {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        }
        throw new IllegalStateException(
                "myrmec.external-api.enabled=true requires either "
                        + "myrmec.external-api.keycloak.issuer-uri or "
                        + "myrmec.external-api.keycloak.jwk-set-uri to be set");
    }

    @Bean
    @Order(1)
    public SecurityFilterChain externalApiSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder externalApiJwtDecoder,
            ServiceAccountJwtAuthenticationConverter serviceAccountConverter) throws Exception {
        http
                .securityMatcher("/api/v1/external/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasRole("SERVICE_ACCOUNT"))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(externalApiJwtDecoder)
                                .jwtAuthenticationConverter(serviceAccountConverter)));
        return http.build();
    }
}
