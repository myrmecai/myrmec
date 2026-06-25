package ai.myrmec.engine.project;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.security.ProjectAccessEvaluator;
import ai.myrmec.engine.project.dto.CreateProjectRequest;
import ai.myrmec.engine.project.dto.UpdateProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #77 — per-project {@code allowed_service_types} column, the
 * {@link ProjectAccessEvaluator#allowsServiceType} gate, and create/update
 * validation in {@link ProjectService}.
 */
@Transactional
class ProjectServiceTypeIT extends IntegrationTestBase {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectAccessEvaluator projectAccess;

    private CreateProjectRequest baseRequest(String name) {
        CreateProjectRequest r = new CreateProjectRequest();
        r.setName(name);
        return r;
    }

    @Test
    void defaultsToBothServiceTypesWhenOmitted() {
        Project p = projectService.create(baseRequest("svc-default-" + UUID.randomUUID()));

        assertThat(p.getAllowedServiceTypes())
                .containsExactlyInAnyOrder("WORKFLOW", "CONVERSATIONAL");
        assertThat(projectAccess.allowsServiceType(p.getId(), "WORKFLOW")).isTrue();
        assertThat(projectAccess.allowsServiceType(p.getId(), "CONVERSATIONAL")).isTrue();
    }

    @Test
    void narrowsToASingleTypeAndGatesTheOther() {
        CreateProjectRequest r = baseRequest("svc-narrow-" + UUID.randomUUID());
        r.setAllowedServiceTypes(List.of("workflow")); // case-insensitive
        Project p = projectService.create(r);

        assertThat(p.getAllowedServiceTypes()).containsExactly("WORKFLOW");
        assertThat(projectAccess.allowsServiceType(p.getId(), "WORKFLOW")).isTrue();
        assertThat(projectAccess.allowsServiceType(p.getId(), "CONVERSATIONAL")).isFalse();
    }

    @Test
    void updateReplacesTheAllowedSet() {
        Project p = projectService.create(baseRequest("svc-update-" + UUID.randomUUID()));

        UpdateProjectRequest u = new UpdateProjectRequest();
        u.setAllowedServiceTypes(List.of("CONVERSATIONAL"));
        Project updated = projectService.update(p.getId(), u);

        assertThat(updated.getAllowedServiceTypes()).containsExactly("CONVERSATIONAL");
        assertThat(projectAccess.allowsServiceType(p.getId(), "WORKFLOW")).isFalse();
    }

    @Test
    void updateWithNullPreservesExisting() {
        CreateProjectRequest r = baseRequest("svc-preserve-" + UUID.randomUUID());
        r.setAllowedServiceTypes(List.of("WORKFLOW"));
        Project p = projectService.create(r);

        UpdateProjectRequest u = new UpdateProjectRequest();
        u.setDescription("touch something else");
        Project updated = projectService.update(p.getId(), u);

        assertThat(updated.getAllowedServiceTypes()).containsExactly("WORKFLOW");
    }

    @Test
    void rejectsUnknownServiceType() {
        CreateProjectRequest r = baseRequest("svc-bad-" + UUID.randomUUID());
        r.setAllowedServiceTypes(List.of("TELEPATHY"));

        assertThatThrownBy(() -> projectService.create(r))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown service type");
    }

    @Test
    void rejectsEmptyServiceTypeList() {
        CreateProjectRequest r = baseRequest("svc-empty-" + UUID.randomUUID());
        r.setAllowedServiceTypes(List.of());

        assertThatThrownBy(() -> projectService.create(r))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least one service type");
    }

    @Test
    void allowsServiceTypeDeniesForUnknownProject() {
        assertThat(projectAccess.allowsServiceType(UUID.randomUUID(), "WORKFLOW")).isFalse();
    }

    @Test
    void projectAttachmentGovernancePersistsOnCreateAndUpdate() {
        CreateProjectRequest create = baseRequest("attach-governance-" + UUID.randomUUID());
        create.setAttachmentsEnabled(false);
        create.setAttachmentRetentionTtlDays(30);
        create.setAttachmentMaxFileSizeBytes(2048L);
        create.setAttachmentTypeAllowlist("text/plain,application/pdf");

        Project p = projectService.create(create);
        assertThat(p.isAttachmentsEnabled()).isFalse();
        assertThat(p.getAttachmentRetentionTtlDays()).isEqualTo(30);
        assertThat(p.getAttachmentMaxFileSizeBytes()).isEqualTo(2048L);
        assertThat(p.getAttachmentTypeAllowlist()).isEqualTo("text/plain,application/pdf");

        UpdateProjectRequest update = new UpdateProjectRequest();
        update.setAttachmentsEnabled(true);
        update.setAttachmentMaxFileSizeBytes(4096L);
        Project updated = projectService.update(p.getId(), update);

        assertThat(updated.isAttachmentsEnabled()).isTrue();
        assertThat(updated.getAttachmentMaxFileSizeBytes()).isEqualTo(4096L);
        assertThat(updated.getAttachmentRetentionTtlDays()).isEqualTo(30);
    }
}
