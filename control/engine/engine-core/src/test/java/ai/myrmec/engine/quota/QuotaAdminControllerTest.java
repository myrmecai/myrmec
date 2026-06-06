package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.quota.dto.CreateQuotaRequest;
import ai.myrmec.engine.quota.dto.QuotaResponse;
import ai.myrmec.engine.quota.dto.UpdateQuotaRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8d &mdash; HTTP surface of the quota admin controller.
 *
 * <p>Covers the happy CRUD path plus the consumption probe used by the
 * UI banner. Hierarchy/ceiling rules are already covered by
 * {@code QuotaServiceTest}; this class focuses on the REST contract.</p>
 */
class QuotaAdminControllerTest extends IntegrationTestBase {

    @Test
    void adminCanCreateListUpdateAndDeleteQuota() {
        UUID scopeId = UUID.randomUUID();
        CreateQuotaRequest req = CreateQuotaRequest.builder()
                .scopeType("ORG")
                .scopeId(scopeId)
                .resourceType("TOKENS")
                .period("DAILY")
                .limitAmount(100_000L)
                .enforced(true)
                .build();

        ResponseEntity<QuotaResponse> created = restTemplate.exchange(
                "/api/v1/admin/quotas",
                HttpMethod.POST,
                new HttpEntity<>(req, adminHeaders()),
                QuotaResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        UUID id = created.getBody().getId();
        assertThat(id).isNotNull();
        assertThat(created.getBody().getLimitAmount()).isEqualTo(100_000L);

        // List filtered by scope returns our row.
        ResponseEntity<List<QuotaResponse>> listed = restTemplate.exchange(
                "/api/v1/admin/quotas?scopeType=ORG&scopeId=" + scopeId,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<QuotaResponse>>() {});
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).extracting(QuotaResponse::getId).contains(id);

        // Update limit.
        UpdateQuotaRequest updateReq = UpdateQuotaRequest.builder()
                .limitAmount(250_000L)
                .enforced(false)
                .build();
        ResponseEntity<QuotaResponse> updated = restTemplate.exchange(
                "/api/v1/admin/quotas/" + id,
                HttpMethod.PUT,
                new HttpEntity<>(updateReq, adminHeaders()),
                QuotaResponse.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().getLimitAmount()).isEqualTo(250_000L);
        assertThat(updated.getBody().isEnforced()).isFalse();

        // Delete.
        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/api/v1/admin/quotas/" + id,
                HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders()),
                Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void consumptionProbeReturnsUnconstrainedWhenNoQuotaConfigured() {
        UUID scopeId = UUID.randomUUID();
        ResponseEntity<Map<String, Object>> res = restTemplate.exchange(
                "/api/v1/admin/quotas/consumption"
                        + "?scopeType=ORG&scopeId=" + scopeId
                        + "&resourceType=TOKENS&amount=0",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().get("blocked")).isEqualTo(false);
        assertThat(res.getBody().get("warning")).isEqualTo(false);
        // No quota matches => unconstrained ceiling is Long.MAX_VALUE.
        Object limit = res.getBody().get("limitAmount");
        assertThat(((Number) limit).longValue()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void consumptionProbeReportsConsumedAndWarningBand() {
        UUID scopeId = UUID.randomUUID();
        // Seed a 1000-token daily limit so a probe of 850 trips the
        // 80% warning band without blocking.
        CreateQuotaRequest req = CreateQuotaRequest.builder()
                .scopeType("ORG")
                .scopeId(scopeId)
                .resourceType("TOKENS")
                .period("DAILY")
                .limitAmount(1_000L)
                .enforced(true)
                .build();
        restTemplate.exchange(
                "/api/v1/admin/quotas",
                HttpMethod.POST,
                new HttpEntity<>(req, adminHeaders()),
                QuotaResponse.class);

        ResponseEntity<Map<String, Object>> res = restTemplate.exchange(
                "/api/v1/admin/quotas/consumption"
                        + "?scopeType=ORG&scopeId=" + scopeId
                        + "&resourceType=TOKENS&amount=850",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("blocked")).isEqualTo(false);
        assertThat(res.getBody().get("warning")).isEqualTo(true);
        assertThat(((Number) res.getBody().get("limitAmount")).longValue()).isEqualTo(1_000L);
    }
}
