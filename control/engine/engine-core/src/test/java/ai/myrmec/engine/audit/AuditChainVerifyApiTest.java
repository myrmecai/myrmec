// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.ProjectSetting;
import ai.myrmec.engine.project.ProjectSettingRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit chain verify API tests — test the REST endpoint and role gating.
 *
 * <p>GET /api/v1/admin/audit-log/verify?scopeType=…&projectId=…
 * → { valid, checkedCount, firstBrokenId, reason }
 */
@Tag("INV-6")
@Tag("SG9")
@DisplayName("Audit Chain Verify API")
class AuditChainVerifyApiTest extends IntegrationTestBase {

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private ProjectSettingRepository projectSettingRepository;

    @Autowired
    private TestDataBuilder data;

    private static final String SCOPE_PROJECT = "PROJECT";

    @Test
    @DisplayName("verify endpoint returns valid=true for a clean chain")
    void verifyReturnsValidForCleanChain() {
        UUID projectId = data.project().create().getId();
        enableChaining(projectId);
        writeProjectEvent(projectId, "CREATED", "entity-1");
        writeProjectEvent(projectId, "UPDATED", "entity-2");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/audit-log/verify?scopeType=PROJECT&projectId=" + projectId,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("valid")).isEqualTo(true);
        assertThat((int) body.get("checkedCount")).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("verify endpoint returns valid=false for a tampered chain")
    void verifyReturnsInvalidForTamperedChain() {
        UUID projectId = data.project().create().getId();
        enableChaining(projectId);
        writeProjectEvent(projectId, "CREATED", "entity-1");
        writeProjectEvent(projectId, "UPDATED", "entity-2");

        // Tamper with the first event's content
        var events = auditEventRepository.findByScopeTypeAndProjectIdOrderByIdAsc(SCOPE_PROJECT, projectId);
        events.get(0).setEventType("TAMPERED");
        auditEventRepository.save(events.get(0));

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/audit-log/verify?scopeType=PROJECT&projectId=" + projectId,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("valid")).isEqualTo(false);
    }

    @Test
    @DisplayName("verify endpoint is role-gated: unauthenticated request is rejected")
    void verifyEndpointRoleGated() {
        UUID projectId = UUID.randomUUID();

        // No auth headers → should be 401/403
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/v1/admin/audit-log/verify?scopeType=PROJECT&projectId=" + projectId,
                Map.class);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    // --- helpers ---

    private void enableChaining(UUID projectId) {
        ProjectSetting setting = new ProjectSetting();
        setting.setProjectId(projectId);
        setting.setSettingKey("audit_integrity_enabled");
        setting.setValueType("STRING");
        setting.setSettingValue("true");
        projectSettingRepository.save(setting);
    }

    private void writeProjectEvent(UUID projectId, String eventType, String entityName) {
        auditEventService.recordEvent(
                "test_entity", UUID.randomUUID(), eventType,
                SCOPE_PROJECT, projectId,
                null, "Verify API Test", null, null,
                null, Map.of("name", entityName), null);
    }
}