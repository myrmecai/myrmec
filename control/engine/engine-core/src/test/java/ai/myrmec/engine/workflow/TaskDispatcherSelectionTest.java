// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.7 workflow cutover: the task keeps its profile binding
 * ({@code task.agent_profile_id} resolves the behavior contract), but HOST
 * SELECTION is capacity-based. Discrimination: hosts whose stored profiles
 * are UNRELATED to the task's profile still receive the dispatch — a
 * profile-keyed selector would find zero candidates and never dispatch.
 *
 * <p>The selection is observed through the unified path: the session is offered
 * to a host that does NOT store the task's profile, and the session row records
 * that host's live instance as its serving instance.</p>
 */
@Transactional
class TaskDispatcherSelectionTest extends WorkflowDispatchSupport {

    @Autowired private TaskDispatcherService dispatcher;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private ai.myrmec.engine.inference.SessionRepository sessionRepository;

    private Project project;
    private AgentProfile taskProfile;
    private User admin;

    @BeforeEach
    void setUp() {
        project = data.project().named("sel").withRepo("https://x.git", "main").create();
        taskProfile = data.agentProfile().named("sel-task-profile").create();
        admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();
    }

    @Test
    @DisplayName("hosts with unrelated profiles still receive the dispatch — selection is capacity-based")
    void dispatchIgnoresHostProfileBinding() throws Exception {
        // The task's profile P — NO host stores it.
        AgentProfile unrelatedQ = data.agentProfile().named("sel-host-q").create();
        AgentProfile unrelatedR = data.agentProfile().named("sel-host-r").create();

        // Two live hosts storing Q and R (neither P).
        SocketHost hostQ = openHost("sel-host-a", unrelatedQ, project, 4);
        SocketHost hostR = openHost("sel-host-b", unrelatedR, project, 4);

        // A PENDING INFERENCE task pinned to profile P.
        Workflow wf = plainWorkflow("sel");
        WorkflowRequest request = runningRequest(wf, "sel");
        WorkflowTask task = pendingTask(request, taskProfile);

        dispatcher.dispatchPendingTasks();

        // A profile-keyed selector would find zero hosts (no host has P)
        // and leave the task PENDING. The capacity selector dispatched, and
        // the offered session is pinned to one of the live hosts' instances.
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus())
                .as("the task dispatched to a host that does NOT store its profile")
                .isEqualTo(TaskStatus.RUNNING);
        assertThat(attemptRepository.findByTaskId(task.getId()))
                .as("an attempt was created for the dispatched task")
                .isNotEmpty();

        UUID sessionId = sessionRepository.findByRefId(request.getId()).stream()
                .filter(s -> "WORKFLOW".equals(s.getServiceType()))
                .findFirst().orElseThrow().getId();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getHostInstanceId())
                .as("the selected host is one of the live candidates")
                .isIn(hostQ.instanceId(), hostR.instanceId());
    }

    // ── fixtures ───────────────────────────────────────────────

    private Workflow plainWorkflow(String tag) {
        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName("sel-wf-" + tag);
        wf.setSteps(java.util.List.<java.util.Map<String, Object>>of(
                java.util.Map.of("id", "analyze", "name", "Analyze")));
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        return workflowRepository.save(wf);
    }

    private WorkflowRequest runningRequest(Workflow wf, String tag) {
        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.PENDING);
        req.setBranch("myrmec/" + tag);
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        return requestRepository.save(req);
    }

    private WorkflowTask pendingTask(WorkflowRequest request, AgentProfile profile) {
        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId("analyze");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(0);
        task.setMaxRetries(0);
        return taskRepository.save(task);
    }
}
