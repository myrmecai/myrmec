// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.workflow.dto.CreateWorkflowRequest;
import ai.myrmec.engine.workflow.dto.WorkflowResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RECON-11: Workflow status transition contract.
 *
 * <p>Asserts:
 * <ul>
 *   <li>DRAFT → PUBLISHED via publish() (allowed)</li>
 *   <li>PUBLISHED → publish() is idempotent</li>
 *   <li>ARCHIVED → publish() is rejected (terminal for publish)</li>
 * </ul>
 *
 * <p>Known gaps: no explicit disable() method; update() allows
 * arbitrary status changes without transition validation.
 */
@Tag("RECON-11")
@Tag("SG5")
@DisplayName("RECON-11: Workflow Status Transition Contract")
class WorkflowStatusTransitionContractTest extends IntegrationTestBase {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ProjectRepository projectRepository;

    private WorkflowResponse createWorkflow() {
        // Create a project first
        Project project = data.project().create();
        UUID projectId = project.getId();
        assertThat(projectId).as("project ID must not be null").isNotNull();

        return workflowService.create(
                new CreateWorkflowRequest(
                        projectId,
                        "RECON11 Test Workflow",
                        "Test",
                        List.of(new ai.myrmec.engine.workflow.dto.WorkflowStepDto(
                                "s1", "Step 1", null, "prompt", null, null, null, null, null)),
                        null, null
                ),
                TEST_ADMIN_ID);
    }

    @Test
    @DisplayName("DRAFT → PUBLISHED via publish() is allowed")
    void draftToPublished() {
        var workflow = createWorkflow();
        assertThat(workflow.status()).isEqualTo(WorkflowStatus.DRAFT);
        var published = workflowService.publish(workflow.id());
        assertThat(published.status()).isEqualTo(WorkflowStatus.PUBLISHED);
    }

    @Test
    @DisplayName("PUBLISHED → publish() is idempotent")
    void publishedToPublishedIdempotent() {
        var workflow = createWorkflow();
        workflowService.publish(workflow.id());
        var rePublished = workflowService.publish(workflow.id());
        assertThat(rePublished.status()).isEqualTo(WorkflowStatus.PUBLISHED);
    }

    @Test
    @DisplayName("ARCHIVED → publish() is rejected (terminal for publish)")
    void archivedToPublishRejected() {
        var workflow = createWorkflow();
        workflowService.publish(workflow.id());
        workflowService.archive(workflow.id());
        assertThatThrownBy(() -> workflowService.publish(workflow.id()))
                .isInstanceOf(IllegalStateException.class);
    }
}