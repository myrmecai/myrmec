package ai.myrmec.engine._system.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Authentication for an External-API caller: a {@link ServiceAccountPrincipal}
 * backed by the validated Keycloak {@link Jwt}. Produced by
 * {@link ServiceAccountJwtAuthenticationConverter} and consumed only by the
 * {@code /api/v1/external/**} filter chain.
 */
public class ServiceAccountAuthenticationToken extends AbstractAuthenticationToken {

    private final ServiceAccountPrincipal principal;
    private final Jwt token;

    public ServiceAccountAuthenticationToken(ServiceAccountPrincipal principal, Jwt token,
                                             Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public ServiceAccountPrincipal getPrincipal() {
        return principal;
    }
}
