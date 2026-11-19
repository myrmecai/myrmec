package ai.myrmec.engine.secret;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.TestDataFactory;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.secret.dto.CreateSecretRequest;
import ai.myrmec.engine.secret.dto.SecretResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link SecretResolverService#resolveReferenceString}
 * (#21 connector-secret wiring). Locks the contract connectors rely on: a
 * free-form token (secret UUID or name) resolves to its primary plaintext,
 * scoped to the owning team, with strict Cross-team isolation.
 */
@Transactional
class SecretResolverServiceTest extends IntegrationTestBase {

    @Autowired
    private SecretService secretService;

    @Autowired
    private SecretResolverService secretResolverService;

    @Test
    void resolvesBearerTokenByUuid() {
        team team = newProject();
        SecretResponse secret = secretService.createForTeam(
                team.getId(),
                bearer("git-pat", "ghp_uuid_token"),
                null);

        Optional<String> resolved =
                secretResolverService.resolveReferenceString(secret.id().toString(), team.getId());

        assertThat(resolved).contains("ghp_uuid_token");
    }

    @Test
    void resolvesProjectSecretByName() {
        team team = newProject();
        secretService.createForTeam(team.getId(), bearer("git-pat", "ghp_named_token"), null);

        Optional<String> resolved =
                secretResolverService.resolveReferenceString("git-pat", team.getId());

        assertThat(resolved).contains("ghp_named_token");
    }

    @Test
    void resolvesGlobalSecretByNameWhenNoProjectMatch() {
        team team = newProject();
        secretService.createGlobal(bearer("shared-pat", "ghp_global_token"), null);

        Optional<String> resolved =
                secretResolverService.resolveReferenceString("shared-pat", team.getId());

        assertThat(resolved).contains("ghp_global_token");
    }

    @Test
    void projectScopedNamePreferredOverGlobalOfSameName() {
        team team = newProject();
        secretService.createGlobal(bearer("dup", "GLOBAL_VALUE"), null);
        secretService.createForTeam(team.getId(), bearer("dup", "PROJECT_VALUE"), null);

        Optional<String> resolved =
                secretResolverService.resolveReferenceString("dup", team.getId());

        assertThat(resolved).contains("PROJECT_VALUE");
    }

    @Test
    void projectSecretNotResolvableFromAnotherProject() {
        team owner = newProject();
        team other = newProject();
        SecretResponse secret = secretService.createForTeam(
                owner.getId(), bearer("owner-only", "secret"), null);

        // By UUID and by name, both scoped to the *other* team -> not reachable.
        assertThat(secretResolverService.resolveReferenceString(secret.id().toString(), other.getId()))
                .isEmpty();
        assertThat(secretResolverService.resolveReferenceString("owner-only", other.getId()))
                .isEmpty();
    }

    @Test
    void systemScopeNullProjectResolvesGlobalsOnly() {
        team team = newProject();
        secretService.createGlobal(bearer("global-only", "GVAL"), null);
        secretService.createForTeam(team.getId(), bearer("proj-only", "PVAL"), null);

        // null teamId (SYSTEM/GROUP scope KB) -> globals reachable, team secrets not.
        assertThat(secretResolverService.resolveReferenceString("global-only", null)).contains("GVAL");
        assertThat(secretResolverService.resolveReferenceString("proj-only", null)).isEmpty();
    }

    @Test
    void extractsPrimaryStringPerPayloadType() {
        team team = newProject();
        secretService.createForTeam(team.getId(),
                req("uname", CredentialType.USERNAME_PASSWORD,
                        new SecretPayload.UsernamePassword("alice", "pw123")), null);
        secretService.createForTeam(team.getId(),
                req("apik", CredentialType.API_KEY,
                        new SecretPayload.ApiKey("KEY123", null)), null);
        secretService.createForTeam(team.getId(),
                req("custom", CredentialType.CUSTOM,
                        new SecretPayload.CustomPayload(Map.of("a", "b"))), null);

        assertThat(secretResolverService.resolveReferenceString("uname", team.getId())).contains("pw123");
        assertThat(secretResolverService.resolveReferenceString("apik", team.getId())).contains("KEY123");
        // CUSTOM has no single primary string.
        assertThat(secretResolverService.resolveReferenceString("custom", team.getId())).isEmpty();
    }

    @Test
    void blankOrUnknownReferenceReturnsEmpty() {
        team team = newProject();
        assertThat(secretResolverService.resolveReferenceString(null, team.getId())).isEmpty();
        assertThat(secretResolverService.resolveReferenceString("   ", team.getId())).isEmpty();
        assertThat(secretResolverService.resolveReferenceString("does-not-exist", team.getId())).isEmpty();
        assertThat(secretResolverService.resolveReferenceString(UUID.randomUUID().toString(), team.getId()))
                .isEmpty();
    }

    // --- helpers -------------------------------------------------------------

    private team newProject() {
        return TeamRepository.save(
                TestDataFactory.projectBuilder("secret-resolver-" + UUID.randomUUID().toString().substring(0, 8))
                        .build());
    }

    private static CreateSecretRequest bearer(String name, String token) {
        return req(name, CredentialType.BEARER_TOKEN, new SecretPayload.BearerToken(token));
    }

    private static CreateSecretRequest req(String name, CredentialType type, SecretPayload payload) {
        return new CreateSecretRequest(name, type, null, payload);
    }
}
