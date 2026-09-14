// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.7 workflow cutover: the task keeps its profile binding
 * ({@code task.agent_profile_id} resolves the behavior contract), but HOST
 * SELECTION is capacity-based. Discrimination: hosts whose stored profiles
 * are UNRELATED to the task's profile still receive the dispatch — a
 * profile-keyed selector would find zero candidates and never dispatch.
 */
@Transactional
class TaskDispatcherSelectionTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private TaskDispatcherService dispatcher;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private AgentRepository agentInstanceRepository;
    @Autowired private AgentHostInstanceRepository agentHostInstanceRepository;
    @Autowired private ai.myrmec.engine.websocket.AgentConnectionManager connectionManager;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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
    @DisplayName("hosts with unrelated profiles still dispatch the task — selection is capacity-based")
    void dispatchIgnoresHostProfileBinding() throws Exception {
        // The task's profile P — NO host stores it.
        AgentProfile unrelatedQ = data.agentProfile().named("sel-host-q").create();
        AgentProfile unrelatedR = data.agentProfile().named("sel-host-r").create();

        // Two live hosts storing Q and R (neither P).
        AgentHost hostQ = data.agent().named("sel-host-a")
                .withProfile(unrelatedQ).inProject(project).create().agent();
        AgentHost hostR = data.agent().named("sel-host-b")
                .withProfile(unrelatedR).inProject(project).create().agent();
        Agent instanceQ = idleInstanceFor(hostQ, "sel-a");
        Agent instanceR = idleInstanceFor(hostR, "sel-b");

        // A PENDING INFERENCE task pinned to profile P.
        Workflow wf = plainWorkflow("sel");
        WorkflowRequest request = runningRequest(wf, "sel");
        WorkflowTask task = pendingTask(request, taskProfile);

        dispatcher.dispatchPendingTasks();

        // A profile-keyed selector would find zero hosts (no host has P)
        // and leave the task PENDING. The capacity selector dispatches.
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus())
                .as("the task dispatched to a host that does NOT store its profile")
                .isEqualTo(TaskStatus.RUNNING);
        assertThat(stored.getAgentInstance()).isNotNull();
        assertThat(stored.getAgentInstance().getAgentHostId())
                .as("the selected host is one of the live candidates")
                .isIn(hostQ.getId(), hostR.getId());
        assertThat(attemptRepository.findByTaskId(task.getId()))
                .as("an attempt was created for the dispatched task")
                .isNotEmpty();
    }

    // ── fixtures ───────────────────────────────────────────────

    private Agent idleInstanceFor(AgentHost host, String hostname) {
        agentHostInstanceRepository.saveAndFlush(AgentHostInstance.open(
                host, null, hostname, 4, Map.of("cpuCount", 8), "engine-node-1"));
        Agent instance = new Agent();
        instance.setAgentHostId(host.getId());
        instance.setHostname(hostname);
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(Agent.Status.IDLE);
        instance.setRegisteredAt(Instant.now());
        instance = agentInstanceRepository.save(instance);
        org.mockito.Mockito.mockingDetails(connectionManager);
        registerStub(instance.getId(), hostname);
        return instance;
    }

    private void registerStub(UUID instanceId, String name) {
        try {
            BlockingQueue<String> outbound = new LinkedBlockingQueue<>();
            org.springframework.web.socket.WebSocketSession session =
                    org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
            java.util.Map<String, Object> attrs = new java.util.HashMap<>();
            attrs.put("agentInstanceId", instanceId);
            attrs.put("agentName", name);
            org.mockito.Mockito.lenient().when(session.getId()).thenReturn("stub-" + instanceId);
            org.mockito.Mockito.lenient().when(session.isOpen()).thenReturn(true);
            org.mockito.Mockito.lenient().when(session.getAttributes()).thenReturn(attrs);
            org.mockito.Mockito.doAnswer(inv -> {
                org.springframework.web.socket.TextMessage msg = inv.getArgument(0);
                outbound.add(msg.getPayload());
                return null;
            }).when(session).sendMessage(org.mockito.ArgumentMatchers.any(
                    org.springframework.web.socket.TextMessage.class));
            connectionManager.register(instanceId, name, session);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

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