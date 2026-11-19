package ai.myrmec.engine.team;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.security.TeamAccessEvaluator;
import ai.myrmec.engine.team.dto.CreateTeamRequest;
import ai.myrmec.engine.team.dto.UpdateTeamRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #77 — per-team {@code allowed_service_types} column, the
 * {@link TeamAccessEvaluator#allowsServiceType} gate, and create/update
 * validation in {@link TeamService}.
 */
@Transactional
class ProjectServiceTypeIT extends IntegrationTestBase {

    @Autowired
    private TeamService TeamService;

    @Autowired
    private TeamAccessEvaluator teamAccess;

    private CreateTeamRequest baseRequest(String name) {
        CreateTeamRequest r = new CreateTeamRequest();
        r.setName(name);
        return r;
    }

    @Test
    void defaultsToBothServiceTypesWhenOmitted() {
        team p = TeamService.create(baseRequest("svc-default-" + UUID.randomUUID()));

        assertThat(p.getAllowedServiceTypes())
                .containsExactlyInAnyOrder("WORKFLOW", "CONVERSATIONAL");
        assertThat(teamAccess.allowsServiceType(p.getId(), "WORKFLOW")).isTrue();
        assertThat(teamAccess.allowsServiceType(p.getId(), "CONVERSATIONAL")).isTrue();
    }

    @Test
    void narrowsToASingleTypeAndGatesTheOther() {
        CreateTeamRequest r = baseRequest("svc-narrow-" + UUID.randomUUID());
        r.setAllowedServiceTypes(List.of("workflow")); // case-insensitive
        team p = TeamService.create(r);

        assertThat(p.getAllowedServiceTypes()).containsExactly("WORKFLOW");
        assertThat(teamAccess.allowsServiceType(p.getId(), "WORKFLOW")).isTrue();
        assertThat(teamAccess.allowsServiceType(p.getId(), "CONVERSATIONAL")).isFalse();
    }

    @Test
    void updateReplacesTheAllowedSet() {
        team p = TeamService.create(baseRequest("svc-update-" + UUID.randomUUID()));

        UpdateTeamRequest u = new UpdateTeamRequest();
        u.setAllowedServiceTypes(List.of("CONVERSATIONAL"));
        team updated = TeamService.update(p.getId(), u);

        assertThat(updated.getAllowedServiceTypes()).containsExactly("CONVERSATIONAL");
        assertThat(teamAccess.allowsServiceType(p.getId(), "WORKFLOW")).isFalse();
    }

    @Test
    void updateWithNullPreservesExisting() {
        CreateTeamRequest r = baseRequest("svc-preserve-" + UUID.randomUUID());
        r.setAllowedServiceTypes(List.of("WORKFLOW"));
        team p = TeamService.create(r);

        UpdateTeamRequest u = new UpdateTeamRequest();
        u.setDescription("touch something else");
        team updated = TeamService.update(p.getId(), u);

        assertThat(updated.getAllowedServiceTypes()).containsExactly("WORKFLOW");
    }

    @Test
    void rejectsUnknownServiceType() {
        CreateTeamRequest r = baseRequest("svc-bad-" + UUID.randomUUID());
        r.setAllowedServiceTypes(List.of("TELEPATHY"));

        assertThatThrownBy(() -> TeamService.create(r))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown service type");
    }

    @Test
    void rejectsEmptyServiceTypeList() {
        CreateTeamRequest r = baseRequest("svc-empty-" + UUID.randomUUID());
        r.setAllowedServiceTypes(List.of());

        assertThatThrownBy(() -> TeamService.create(r))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least one service type");
    }

    @Test
    void allowsServiceTypeDeniesForUnknownProject() {
        assertThat(teamAccess.allowsServiceType(UUID.randomUUID(), "WORKFLOW")).isFalse();
    }

    @Test
    void projectAttachmentGovernancePersistsOnCreateAndUpdate() {
        CreateTeamRequest create = baseRequest("attach-governance-" + UUID.randomUUID());
        create.setAttachmentsEnabled(false);
        create.setAttachmentRetentionTtlDays(30);
        create.setAttachmentMaxFileSizeBytes(2048L);
        create.setAttachmentTypeAllowlist("text/plain,application/pdf");

        team p = TeamService.create(create);
        assertThat(p.isAttachmentsEnabled()).isFalse();
        assertThat(p.getAttachmentRetentionTtlDays()).isEqualTo(30);
        assertThat(p.getAttachmentMaxFileSizeBytes()).isEqualTo(2048L);
        assertThat(p.getAttachmentTypeAllowlist()).isEqualTo("text/plain,application/pdf");

        UpdateTeamRequest update = new UpdateTeamRequest();
        update.setAttachmentsEnabled(true);
        update.setAttachmentMaxFileSizeBytes(4096L);
        team updated = TeamService.update(p.getId(), update);

        assertThat(updated.isAttachmentsEnabled()).isTrue();
        assertThat(updated.getAttachmentMaxFileSizeBytes()).isEqualTo(4096L);
        assertThat(updated.getAttachmentRetentionTtlDays()).isEqualTo(30);
    }
}
