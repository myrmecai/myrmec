package ai.myrmec.engine._system.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import ai.myrmec.engine.serviceaccount.ServiceAccount;
import ai.myrmec.engine.serviceaccount.ServiceAccountService;

@ExtendWith(MockitoExtension.class)
class ServiceAccountJwtAuthenticationConverterTest {

    @Mock
    private ServiceAccountService serviceAccountService;

    @InjectMocks
    private ServiceAccountJwtAuthenticationConverter converter;

    private static Jwt jwtWithClaims(java.util.Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static ServiceAccount account(UUID id, UUID projectId, String clientId, String name) {
        ServiceAccount account = new ServiceAccount();
        account.setId(id);
        account.setProjectId(projectId);
        account.setKeycloakClientId(clientId);
        account.setName(name);
        account.setEnabled(true);
        return account;
    }

    @Test
    void resolvesActiveAccountFromAzpClaim() {
        UUID id = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(serviceAccountService.resolveActiveByClientId(eq("helpdesk-client")))
                .thenReturn(Optional.of(account(id, projectId, "helpdesk-client", "Helpdesk")));

        AbstractAuthenticationToken auth = converter.convert(
                jwtWithClaims(java.util.Map.of("azp", "helpdesk-client")));

        assertThat(auth).isInstanceOf(ServiceAccountAuthenticationToken.class);
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_SERVICE_ACCOUNT");

        ServiceAccountPrincipal principal = (ServiceAccountPrincipal) auth.getPrincipal();
        assertThat(principal.getServiceAccountId()).isEqualTo(id);
        assertThat(principal.getProjectId()).isEqualTo(projectId);
        assertThat(principal.getKeycloakClientId()).isEqualTo("helpdesk-client");
        assertThat(principal.getDisplayName()).isEqualTo("Helpdesk");
        assertThat(principal.getName()).isEqualTo(id.toString());
    }

    @Test
    void fallsBackToClientIdClaimWhenAzpAbsent() {
        UUID id = UUID.randomUUID();
        when(serviceAccountService.resolveActiveByClientId(eq("svc-client")))
                .thenReturn(Optional.of(account(id, UUID.randomUUID(), "svc-client", "Svc")));

        AbstractAuthenticationToken auth = converter.convert(
                jwtWithClaims(java.util.Map.of("client_id", "svc-client")));

        assertThat(((ServiceAccountPrincipal) auth.getPrincipal()).getServiceAccountId()).isEqualTo(id);
    }

    @Test
    void rejectsUnknownOrDisabledAccount() {
        when(serviceAccountService.resolveActiveByClientId(eq("ghost")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> converter.convert(jwtWithClaims(java.util.Map.of("azp", "ghost"))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsTokenWithoutClientIdClaim() {
        assertThatThrownBy(() -> converter.convert(jwtWithClaims(java.util.Map.of("sub", "nobody"))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }
}
