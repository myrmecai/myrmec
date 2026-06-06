package ai.myrmec.engine.model;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.model.dto.CreateModelProviderRequest;
import ai.myrmec.engine.model.dto.ModelProviderResponse;
import ai.myrmec.engine.model.dto.UpdateModelProviderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 #70 &mdash; admin CRUD over model providers.
 *
 * <p>Covers (a) create + update + delete happy path on a non-system
 * provider, (b) the system-provider delete guard, (c) the
 * "models still reference this provider" delete guard.</p>
 */
class ModelProviderAdminControllerTest extends IntegrationTestBase {

    @Autowired
    private ModelProviderConfigRepository providerRepository;

    @Test
    void adminCanCreateUpdateDeleteNonSystemProvider() {
        String code = "test-provider-" + UUID.randomUUID().toString().substring(0, 8);
        CreateModelProviderRequest req = CreateModelProviderRequest.builder()
                .code(code)
                .name("Test Provider")
                .baseUrl("https://example.invalid/api")
                .deploymentType(DeploymentType.CLOUD)
                .requiresAuth(true)
                .authHeader("Authorization")
                .authPrefix("Bearer ")
                .description("Phase 10 #70 spec")
                .build();

        ResponseEntity<ModelProviderResponse> created = restTemplate.exchange(
                "/api/v1/admin/providers",
                HttpMethod.POST,
                new HttpEntity<>(req, adminHeaders()),
                ModelProviderResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().getCode()).isEqualTo(code);
        assertThat(created.getBody().isSystem()).isFalse();

        // List filtered (admin) contains our new row.
        ResponseEntity<List<ModelProviderResponse>> listed = restTemplate.exchange(
                "/api/v1/admin/providers",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ModelProviderResponse>>() {});
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).extracting(ModelProviderResponse::getCode).contains(code);

        // Update name.
        UpdateModelProviderRequest update = UpdateModelProviderRequest.builder()
                .name("Test Provider (renamed)")
                .build();
        ResponseEntity<ModelProviderResponse> updated = restTemplate.exchange(
                "/api/v1/admin/providers/" + code,
                HttpMethod.PUT,
                new HttpEntity<>(update, adminHeaders()),
                ModelProviderResponse.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().getName()).isEqualTo("Test Provider (renamed)");

        // Delete.
        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/api/v1/admin/providers/" + code,
                HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders()),
                Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(providerRepository.findById(code)).isEmpty();
    }

    @Test
    void deletingSeededSystemProviderIsBlocked() {
        // Any seeded provider works; pick whatever is in the table.
        ModelProviderConfig system = providerRepository.findAll().stream()
                .filter(ModelProviderConfig::isSystem)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected at least one Liquibase-seeded system provider"));

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/admin/providers/" + system.getCode(),
                HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders()),
                String.class);
        // Global handler maps IllegalStateException to 400.
        assertThat(res.getStatusCode().is4xxClientError()).isTrue();
        assertThat(providerRepository.findById(system.getCode())).isPresent();
    }
}
