package ai.myrmec.engine.quota;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.TestDataFactory;
import ai.myrmec.engine.audit.AuditLogEntry;
import ai.myrmec.engine.audit.AuditLogEntryRepository;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 &mdash; CRUD + audit-trail + hierarchy walk behaviour for
 * {@link QuotaService}.
 */
class QuotaServiceTest extends IntegrationTestBase {

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private AuditLogEntryRepository auditLogEntryRepository;

    @Autowired
    private ProjectRepository projectRepository;

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

    @Test
    void hierarchy_projectCannotExceedGroupCeiling() {
        // Seed a project in the default group, and a group-scoped 500 cap.
        Project project = projectRepository.save(
                TestDataFactory.projectBuilder("hierarchy-proj").build());
        quotaService.create(
                Quota.Scope.GROUP, Group.DEFAULT_GROUP_ID,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                500L, true, null, null);

        // 400 under the 500 cap — allowed.
        Quota allowed = quotaService.create(
                Quota.Scope.PROJECT, project.getId(),
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                400L, true, null, null);
        assertThat(allowed.getLimitAmount()).isEqualTo(400L);

        // 600 over the 500 cap — rejected.
        assertThatThrownBy(() -> quotaService.create(
                Quota.Scope.PROJECT, project.getId(),
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                600L, true, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds parent");
    }

    @Test
    void hierarchy_groupCannotExceedOrgCeiling() {
        UUID orgId = UUID.randomUUID();
        quotaService.create(
                Quota.Scope.ORG, orgId,
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                1_000L, true, null, null);

        // Group quota of 2000 exceeds the ORG ceiling of 1000.
        assertThatThrownBy(() -> quotaService.create(
                Quota.Scope.GROUP, UUID.randomUUID(),
                Quota.ResourceType.TOKENS, Quota.Period.DAILY,
                2_000L, true, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORG ceiling");
    }

    @Test
    void hierarchy_orgScope_acceptsAnyAmount() {
        // ORG is top of hierarchy; no parent to clamp against.
        Quota q = quotaService.create(
                Quota.Scope.ORG, UUID.randomUUID(),
                Quota.ResourceType.COST_USD_CENTS, Quota.Period.MONTHLY_CALENDAR,
                10_000_000L, true, null, null);
        assertThat(q.getLimitAmount()).isEqualTo(10_000_000L);
    }
}
