package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.workflow.dto.StartWorkflowRequest;
import ai.myrmec.engine.workflow.dto.WorkflowRequestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Phase 10 #74 &mdash; assertion that {@link WorkflowRequestService#start}
 * leaves the {@link WorkflowRequest} in {@link RequestStatus#PENDING}
 * (the V1 bug fix). The flip to {@link RequestStatus#RUNNING} is the
 * job of {@code TaskDispatcherService} and only happens after the first
 * task is actually picked up by an agent.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowRequestStatusLifecycleTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRequestRepository requestRepository;
    @Mock private WorkflowTaskRepository taskRepository;
    @Mock private AgentProfileRepository agentProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private QuotaPolicyEngine quotaPolicyEngine;

    @InjectMocks
    private WorkflowRequestService workflowRequestService;

    private UUID userId;
    private UUID workflowId;
    private Workflow workflow;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        workflowId = UUID.randomUUID();

        Project project = new Project();
        project.setId(UUID.randomUUID());
        project.setName("phase10-bug74");

        workflow = new Workflow();
        workflow.setId(workflowId);
        workflow.setProject(project);
        workflow.setName("phase10-bug74-wf");
        workflow.setVersion(1);
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        // No steps &mdash; createInitialTasks is a no-op, which is exactly
        // what we want: this test cares only about the request lifecycle.
        workflow.setSteps(new ArrayList<>());

        user = new User();
        user.setId(userId);
        user.setEmail("phase10-bug74@test.local");
        user.setName("Phase10 Bug74");

        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(quotaPolicyEngine.check(any(), any(), any(), anyLong()))
                .thenReturn(QuotaDecision.unconstrained());
        lenient().when(requestRepository.save(any(WorkflowRequest.class)))
                .thenAnswer(inv -> {
                    WorkflowRequest r = inv.getArgument(0);
                    if (r.getId() == null) {
                        r.setId(UUID.randomUUID());
                    }
                    return r;
                });
    }

    @Test
    void startLeavesRequestInPendingUntilFirstDispatch() {
        StartWorkflowRequest req = new StartWorkflowRequest(workflowId, Map.of("k", "v"));

        WorkflowRequestResponse response = workflowRequestService.start(req, userId);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(RequestStatus.PENDING);

        ArgumentCaptor<WorkflowRequest> savedCaptor = ArgumentCaptor.forClass(WorkflowRequest.class);
        org.mockito.Mockito.verify(requestRepository, org.mockito.Mockito.atLeastOnce())
                .save(savedCaptor.capture());
        List<WorkflowRequest> saves = savedCaptor.getAllValues();
        WorkflowRequest persisted = saves.get(saves.size() - 1);
        assertThat(persisted.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(persisted.getStartedAt()).isNull();
    }

    @Test
    void startRejectsDraftWorkflow() {
        workflow.setStatus(WorkflowStatus.DRAFT);
        StartWorkflowRequest req = new StartWorkflowRequest(workflowId, Map.of());

        try {
            workflowRequestService.start(req, userId);
            org.junit.jupiter.api.Assertions.fail("Expected IllegalStateException");
        } catch (IllegalStateException ex) {
            assertThat(ex.getMessage()).contains("published");
        }
    }

    @Test
    void startWithMissingWorkflowReturns404() {
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.empty());
        StartWorkflowRequest req = new StartWorkflowRequest(workflowId, Map.of());

        try {
            workflowRequestService.start(req, userId);
            org.junit.jupiter.api.Assertions.fail("Expected ResourceNotFoundException");
        } catch (ResourceNotFoundException ex) {
            assertThat(ex.getMessage()).contains("Workflow");
        }
    }
}
