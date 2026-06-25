package ai.myrmec.engine._system.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import ai.myrmec.engine.serviceaccount.ServiceAccount;
import ai.myrmec.engine.serviceaccount.ServiceAccountService;

import lombok.RequiredArgsConstructor;

/**
 * Resolves a Keycloak-validated client-credentials {@link Jwt} into a
 * {@link ServiceAccountAuthenticationToken}.
 *
 * <p>Signature, issuer and expiry have already been verified by the
 * resource-server {@code JwtDecoder} before this converter runs; here we only
 * map the token's client id ({@code azp}, falling back to {@code client_id}) to
 * an <em>active</em> {@link ServiceAccount}. An unknown or disabled account is
 * rejected as an invalid token so the caller receives 401.</p>
 */
@Component
@RequiredArgsConstructor
public class ServiceAccountJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final SimpleGrantedAuthority SERVICE_ACCOUNT_AUTHORITY =
            new SimpleGrantedAuthority("ROLE_SERVICE_ACCOUNT");

    private final ServiceAccountService serviceAccountService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String clientId = extractClientId(jwt);
        if (!StringUtils.hasText(clientId)) {
            throw new InvalidBearerTokenException("Token is missing a client id (azp/client_id) claim");
        }

        ServiceAccount account = serviceAccountService.resolveActiveByClientId(clientId)
                .orElseThrow(() -> new InvalidBearerTokenException(
                        "No active service account for client id"));

        ServiceAccountPrincipal principal = new ServiceAccountPrincipal(
                account.getId(), account.getProjectId(),
                account.getKeycloakClientId(), account.getName());

        return new ServiceAccountAuthenticationToken(principal, jwt, List.of(SERVICE_ACCOUNT_AUTHORITY));
    }

    private String extractClientId(Jwt jwt) {
        String azp = jwt.getClaimAsString("azp");
        if (StringUtils.hasText(azp)) {
            return azp;
        }
        return jwt.getClaimAsString("client_id");
    }
}
