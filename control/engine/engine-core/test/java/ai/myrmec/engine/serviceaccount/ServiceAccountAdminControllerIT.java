package ai.myrmec.engine.serviceaccount;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.TestDataFactory;
import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.serviceaccount.dto.CreateServiceAccountRequest;
import ai.myrmec.engine.serviceaccount.dto.ServiceAccountResponse;
import ai.myrmec.engine.serviceaccount.dto.UpdateServiceAccountRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the External-API service-account admin surface (#95).
 */
class ServiceAccountAdminControllerIT extends IntegrationTestBase {

    private UUID teamId() {
        team p = TeamRepository.save(
                TestDataFactory.projectBuilder("SA Test team " + UUID.randomUUID()).build());
        return p.getId();
    }

    private CreateServiceAccountRequest request(UUID teamId, String clientId) {
        return new CreateServiceAccountRequest(teamId, "Helpdesk Bot", "Drives the support widget", clientId, null);
    }

    @Test
    void createsServiceAccountWithDefaults() {
        UUID teamId = teamId();

        ResponseEntity<ServiceAccountResponse> response = restTemplate.exchange(
                "/api/v1/admin/service-accounts", HttpMethod.POST,
                new HttpEntity<>(request(teamId, "helpdesk-client"), adminHeaders()),
                ServiceAccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ServiceAccountResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isNotNull();
        assertThat(body.teamId()).isEqualTo(teamId);
        assertThat(body.keycloakClientId()).isEqualTo("helpdesk-client");
        assertThat(body.enabled()).isTrue();
        assertThat(body.rateLimitPerMin()).isEqualTo(60);
        assertThat(body.createdBy()).isEqualTo(TEST_ADMIN_ID);

        // Persisted + resolvable by client id (the auth hot path).
        assertThat(serviceAccountRepository.findByKeycloakClientId("helpdesk-client")).isPresent();
    }

    @Test
    void rejectsDuplicateKeycloakClientId() {
        UUID teamId = teamId();
        restTemplate.exchange("/api/v1/admin/service-accounts", HttpMethod.POST,
                new HttpEntity<>(request(teamId, "dupe-client"), adminHeaders()),
                ServiceAccountResponse.class);

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/service-accounts", HttpMethod.POST,
                new HttpEntity<>(request(teamId, "dupe-client"), adminHeaders()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("DUPLICATE_CODE");
    }

    @Test
    void rejectsUnknownProject() {
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/service-accounts", HttpMethod.POST,
                new HttpEntity<>(request(UUID.randomUUID(), "orphan-client"), adminHeaders()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void getsAndUpdatesServiceAccount() {
        UUID teamId = teamId();
        ServiceAccountResponse created = restTemplate.exchange(
                "/api/v1/admin/service-accounts", HttpMethod.POST,
                new HttpEntity<>(request(teamId, "tune-client"), adminHeaders()),
                ServiceAccountResponse.class).getBody();
        assertThat(created).isNotNull();

        // GET single
        ResponseEntity<ServiceAccountResponse> got = restTemplate.exchange(
                "/api/v1/admin/service-accounts/" + created.id(), HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), ServiceAccountResponse.class);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);

        // PUT: disable + retune rate limit
        UpdateServiceAccountRequest update = new UpdateServiceAccountRequest(null, null, false, 120);
        ResponseEntity<ServiceAccountResponse> patched = restTemplate.exchange(
                "/api/v1/admin/service-accounts/" + created.id(), HttpMethod.PUT,
                new HttpEntity<>(update, adminHeaders()), ServiceAccountResponse.class);

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody()).isNotNull();
        assertThat(patched.getBody().enabled()).isFalse();
        assertThat(patched.getBody().rateLimitPerMin()).isEqualTo(120);

        // Disabled accounts no longer resolve on the auth hot path.
        assertThat(serviceAccountRepository.findByKeycloakClientId("tune-client"))
                .get().extracting(ServiceAccount::isEnabled).isEqualTo(false);
    }

    @Test
    void returnsNotFoundForUnknownId() {
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/service-accounts/" + UUID.randomUUID(), HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listsFilteredByProject() {
        UUID teamA = teamId();
        UUID teamB = teamId();
        restTemplate.exchange("/api/v1/admin/service-accounts", HttpMethod.POST,
                new HttpEntity<>(request(teamA, "list-a-1"), adminHeaders()), ServiceAccountResponse.class);
        restTemplate.exchange("/api/v1/admin/service-accounts", HttpMethod.POST,
                new HttpEntity<>(request(teamB, "list-b-1"), adminHeaders()), ServiceAccountResponse.class);

        ResponseEntity<ServiceAccountResponse[]> filtered = restTemplate.exchange(
                "/api/v1/admin/service-accounts?teamId=" + teamA, HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), ServiceAccountResponse[].class);

        assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(filtered.getBody()).isNotNull();
        assertThat(filtered.getBody()).hasSize(1);
        assertThat(filtered.getBody()[0].keycloakClientId()).isEqualTo("list-a-1");
    }

    @Test
    void forbidsNonAdminCallers() {
        UUID teamId = teamId();
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/service-accounts", HttpMethod.POST,
                new HttpEntity<>(request(teamId, "sneaky-client"), userHeaders(TEST_ADMIN_ID, teamId)),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
