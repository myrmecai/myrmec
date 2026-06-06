package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.audit.AuditLogEntry;
import ai.myrmec.engine.audit.AuditLogEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 &mdash; CRUD + audit-trail behaviour for {@link QuotaService}.
 */
class QuotaServiceTest extends IntegrationTestBase {

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private AuditLogEntryRepository auditLogEntryRepository;

    @Test
    void create_persists_andEmitsAuditRow() {
        UUID projectId = UUID.randomUUID();
        Quota q = quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                500L, true, null, null);

        assertThat(q.getId()).isNotNull();
        assertThat(q.getLimitAmount()).isEqualTo(500L);

        List<AuditLogEntry> rows = auditLogEntryRepository.findAll();
        assertThat(rows).anyMatch(r ->
                "QUOTA_CREATED".equals(r.getAction())
                        && "Quota".equals(r.getResourceType())
                        && q.getId().equals(r.getResourceId()));
    }

    @Test
    void update_changesLimit_andEmitsAuditRow() {
        UUID projectId = UUID.randomUUID();
        Quota q = quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                500L, true, null, null);

        Quota updated = quotaService.update(q.getId(), 1000L, false, null);
        assertThat(updated.getLimitAmount()).isEqualTo(1000L);
        assertThat(updated.isEnforced()).isFalse();

        long updatedAudits = auditLogEntryRepository.findAll().stream()
                .filter(r -> "QUOTA_UPDATED".equals(r.getAction()))
                .count();
        assertThat(updatedAudits).isEqualTo(1L);
    }

    @Test
    void delete_removesRow_andEmitsAuditRow() {
        UUID projectId = UUID.randomUUID();
        Quota q = quotaService.create(
                Quota.Scope.PROJECT, projectId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                500L, true, null, null);

        quotaService.delete(q.getId());

        assertThat(quotaService.findByScope(Quota.Scope.PROJECT, projectId)).isEmpty();
        long deletedAudits = auditLogEntryRepository.findAll().stream()
                .filter(r -> "QUOTA_DELETED".equals(r.getAction()))
                .count();
        assertThat(deletedAudits).isEqualTo(1L);
    }

    @Test
    void delete_unknownId_throws() {
        assertThatThrownBy(() -> quotaService.delete(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
