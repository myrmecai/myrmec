package ai.myrmec.engine.mywork;

import ai.myrmec.engine._system.security.ProjectAccessEvaluator;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationMessageRepository;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.mywork.dto.MyApprovalRow;
import ai.myrmec.engine.mywork.dto.MyArchivedRow;
import ai.myrmec.engine.mywork.dto.MyAssistantRow;
import ai.myrmec.engine.mywork.dto.MyContinueRow;
import ai.myrmec.engine.mywork.dto.MyWorkSummaryResponse;
import ai.myrmec.engine.mywork.dto.MyWorkflowRow;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.service.ServiceType;
import ai.myrmec.engine.workflow.RequestStatus;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRepository;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowRequestRepository;
import ai.myrmec.engine.workflow.WorkflowStatus;
import ai.myrmec.engine.workflow.WorkflowTask;
import ai.myrmec.engine.workflow.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only aggregator behind the unified "My Work" landing surface (UC-013).
 *
 * <p>My Work is a personal, cross-project view: it never owns data and never
 * writes. Every query starts from the set of projects the caller can
 * {@code VIEW} (resolved here, because {@code GET /api/v1/projects} is
 * unscoped), optionally narrowed by a {@code projectIds} chip filter, and then
 * rolls up the four tabs — Workflows, Conversations (Assistants), Approvals,
 * and Archived. Approval and archive <em>actions</em> are deliberately absent;
 * the UI drives those through the existing per-resource write endpoints so
 * there is exactly one authorization path per mutation.</p>
 *
 * <p>Scale assumption is admin-grade (the same one the Service-Type registry
 * makes): the project set is small enough to filter in memory. The heavier
 * fan-outs (per-workflow executions, per-assistant sessions) are bounded by the
 * scoped project set.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyWorkService {

    private final ProjectRepository projectRepository;
    private final ProjectAccessEvaluator projectAccessEvaluator;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRequestRepository workflowRequestRepository;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final AssistantRepository assistantRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;

    // ------------------------------------------------------------------ summary

    public MyWorkSummaryResponse summary(Authentication authentication, List<UUID> projectIds) {
        Scope scope = resolveScope(authentication, projectIds);

        long workflowsCreated = 0;
        long workflowsActive = 0;
        long archivedWorkflows = 0;
        for (Workflow w : scope.workflows()) {
            if (w.getStatus() == WorkflowStatus.ARCHIVED) {
                archivedWorkflows++;
                continue;
            }
            workflowsCreated++;
            if (hasActiveExecution(w.getId())) {
                workflowsActive++;
            }
        }

        Map<UUID, List<Conversation>> activeByAssistant = activeSessionsByAssistant(scope);
        long conversationsCreated = 0;
        long conversationsActive = 0;
        long archivedAssistants = 0;
        for (Assistant a : scope.assistants()) {
            if (a.getArchivedAt() != null) {
                archivedAssistants++;
                continue;
            }
            conversationsCreated++;
            List<Conversation> sessions = activeByAssistant.get(a.getId());
            if (sessions != null && !sessions.isEmpty()) {
                conversationsActive++;
            }
        }

        long approvalsPending = pendingApprovalRows(scope, callerIdOf(authentication)).size();

        boolean firstRun = scope.workflows().isEmpty() && scope.assistants().isEmpty();
        boolean canCreateWorkflow = canCreateServiceOfType(authentication, scope, ServiceType.WORKFLOW);
        boolean canCreateConversation = canCreateServiceOfType(authentication, scope, ServiceType.CONVERSATIONAL);

        return new MyWorkSummaryResponse(
                new MyWorkSummaryResponse.TabCounter(workflowsActive, workflowsCreated),
                new MyWorkSummaryResponse.TabCounter(conversationsActive, conversationsCreated),
                approvalsPending,
                archivedWorkflows + archivedAssistants,
                firstRun,
                canCreateWorkflow,
                canCreateConversation);
    }

    // ---------------------------------------------------------------- workflows

    public List<MyWorkflowRow> workflows(
            Authentication authentication, List<UUID> projectIds, WorkflowStatus status, String q) {
        Scope scope = resolveScope(authentication, projectIds);
        String needle = normalize(q);
        List<MyWorkflowRow> rows = new ArrayList<>();
        for (Workflow w : scope.workflows()) {
            if (w.getStatus() == WorkflowStatus.ARCHIVED) {
                continue; // archived definitions live in the Archived tab
            }
            if (status != null && w.getStatus() != status) {
                continue;
            }
            if (!matches(needle, w.getName(), w.getDescription())) {
                continue;
            }
            String projectName = scope.projectName(w.getProject().getId());
            rows.add(MyWorkflowRow.of(w, projectName, workflowRequestRepository.findByWorkflowId(w.getId())));
        }
        rows.sort(Comparator.comparing(MyWorkflowRow::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    // ------------------------------------------------------------ conversations

    public List<MyAssistantRow> conversations(Authentication authentication, List<UUID> projectIds, String q) {
        Scope scope = resolveScope(authentication, projectIds);
        Map<UUID, List<Conversation>> activeByAssistant = activeSessionsByAssistant(scope);
        String needle = normalize(q);
        List<MyAssistantRow> rows = new ArrayList<>();
        for (Assistant a : scope.assistants()) {
            if (a.getArchivedAt() != null) {
                continue; // archived assistants live in the Archived tab
            }
            if (!matches(needle, a.getName(), a.getDescription())) {
                continue;
            }
            List<Conversation> sessions = activeByAssistant.getOrDefault(a.getId(), List.of());
            Instant lastSessionAt = sessions.stream()
                    .map(MyWorkService::sessionTimestamp)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            rows.add(MyAssistantRow.of(a, scope.projectName(a.getProjectId()), sessions.size(), lastSessionAt));
        }
        rows.sort(Comparator.comparing(MyAssistantRow::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** The signed-in user's own recently-active sessions, for the Continue rail (newest first, capped). */
    public List<MyContinueRow> continueRail(Authentication authentication, UUID userId, List<UUID> projectIds, int limit) {
        Scope scope = resolveScope(authentication, projectIds);
        Map<UUID, String> assistantNames = new HashMap<>();
        return conversationRepository.findByCreatedByOrderByUpdatedAtDesc(userId).stream()
                .filter(c -> c.getStatus() == Conversation.Status.ACTIVE)
                .filter(c -> scope.contains(c.getProjectId()))
                .limit(Math.max(0, limit))
                .map(c -> MyContinueRow.of(c, assistantName(c.getAssistantId(), assistantNames)))
                .toList();
    }

    // ------------------------------------------------------------------ approvals

    public List<MyApprovalRow> approvals(Authentication authentication, List<UUID> projectIds, String q) {
        Scope scope = resolveScope(authentication, projectIds);
        String needle = normalize(q);
        return pendingApprovalRows(scope, callerIdOf(authentication)).stream()
                .filter(r -> matches(needle, r.summary(), r.projectName()))
                // Soonest-expiring first; rows without an expiry sort last.
                .sorted(Comparator.comparing(
                        MyApprovalRow::expiresAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    // ------------------------------------------------------------------ archived

    public List<MyArchivedRow> archived(
            Authentication authentication, List<UUID> projectIds, MyArchivedRow.Type type, String q) {
        Scope scope = resolveScope(authentication, projectIds);
        String needle = normalize(q);
        List<MyArchivedRow> rows = new ArrayList<>();
        if (type == null || type == MyArchivedRow.Type.WORKFLOW) {
            for (Workflow w : scope.workflows()) {
                if (w.getStatus() == WorkflowStatus.ARCHIVED && matches(needle, w.getName(), w.getDescription())) {
                    rows.add(MyArchivedRow.ofWorkflow(w, scope.projectName(w.getProject().getId())));
                }
            }
        }
        if (type == null || type == MyArchivedRow.Type.ASSISTANT) {
            for (Assistant a : scope.assistants()) {
                if (a.getArchivedAt() != null && matches(needle, a.getName(), a.getDescription())) {
                    rows.add(MyArchivedRow.ofAssistant(a, scope.projectName(a.getProjectId())));
                }
            }
        }
        // Most-recently archived first; rows without a timestamp sort last.
        rows.sort(Comparator.comparing(
                MyArchivedRow::archivedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    // -------------------------------------------------------------- internals

    /** All still-PENDING approval requests (conversation + execution sources) whose project is in scope, as rows.
     *
     * <p>§17.4 approver targeting (workflows only): an orchestration /
     * workflow-execution approval is decided by exactly one human — the
     * triggering user ({@code WorkflowRequest.createdBy}). The tab narrows
     * those rows to {@code createdBy = caller}; conversation approvals
     * keep their interactive-session model (any project member sees the
     * request — the second-person review semantic).</p> */
    private List<MyApprovalRow> pendingApprovalRows(Scope scope, UUID callerId) {
        List<MyApprovalRow> rows = new ArrayList<>();
        
        // Query conversation-source approvals (APPROVAL_REQUEST with PENDING status)
        List<ConversationMessage> pendingConversations = conversationMessageRepository.findByRoleAndApprovalStatus(
                ConversationMessage.Role.APPROVAL_REQUEST, ConversationMessage.ApprovalStatus.PENDING);
        if (!pendingConversations.isEmpty()) {
            Map<UUID, Conversation> conversations = new HashMap<>();
            for (ConversationMessage msg : pendingConversations) {
                Conversation conversation = conversations.computeIfAbsent(
                        msg.getConversationId(),
                        id -> conversationRepository.findById(id).orElse(null));
                if (conversation == null || !scope.contains(conversation.getProjectId())) {
                    continue;
                }
                rows.add(MyApprovalRow.fromConversation(msg, conversation, scope.projectName(conversation.getProjectId())));
            }
        }
        
        // Query execution-source approvals (WorkflowTask with approval_status=PENDING).
        // §17.4: workflow-sourced requests narrow to the triggering user —
        // decisions from any other user are rejected under the task row
        // lock, so the tab never surfaces an undecidable request.
        List<WorkflowTask> pendingExecutions = workflowTaskRepository.findByApprovalStatus("PENDING");
        if (!pendingExecutions.isEmpty()) {
            for (WorkflowTask task : pendingExecutions) {
                // Navigate to project via request → workflow → project
                WorkflowRequest request = task.getRequest();
                if (request == null) continue;
                Workflow workflow = request.getWorkflow();
                if (workflow == null) continue;
                UUID projectId = workflow.getProject().getId();
                if (!scope.contains(projectId)) {
                    continue;
                }
                UUID triggerer = request.getCreatedBy() != null
                        ? request.getCreatedBy().getId() : null;
                if (callerId == null || triggerer == null || !triggerer.equals(callerId)) {
                    continue;
                }
                rows.add(MyApprovalRow.fromExecution(task, scope.projectName(projectId)));
            }
        }
        
        return rows;
    }

    /** The caller's user id from the authentication principal (null when
     * not a user principal — agent/system callers see no approvals). */
    private UUID callerIdOf(Authentication authentication) {
        if (authentication.getPrincipal() instanceof ai.myrmec.engine.user.UserPrincipal principal) {
            return principal.getUserId();
        }
        return null;
    }

    private boolean hasActiveExecution(UUID workflowId) {
        return workflowRequestRepository.findByWorkflowId(workflowId).stream().anyMatch(MyWorkService::isActive);
    }

    /** Index of currently-ACTIVE conversations across the scope, keyed by assistantId (skips standalone sessions). */
    private Map<UUID, List<Conversation>> activeSessionsByAssistant(Scope scope) {
        Map<UUID, List<Conversation>> byAssistant = new HashMap<>();
        for (Project p : scope.projectValues()) {
            for (Conversation c : conversationRepository.findByProjectIdOrderByUpdatedAtDesc(p.getId())) {
                if (c.getStatus() != Conversation.Status.ACTIVE || c.getAssistantId() == null) {
                    continue;
                }
                byAssistant.computeIfAbsent(c.getAssistantId(), k -> new ArrayList<>()).add(c);
            }
        }
        return byAssistant;
    }

    private boolean canCreateServiceOfType(Authentication authentication, Scope scope, ServiceType type) {
        for (Project p : scope.projectValues()) {
            if (projectAccessEvaluator.canEdit(p.getId(), authentication)
                    && projectAccessEvaluator.allowsServiceType(p.getId(), type.name())) {
                return true;
            }
        }
        return false;
    }

    private String assistantName(UUID assistantId, Map<UUID, String> cache) {
        if (assistantId == null) {
            return null;
        }
        return cache.computeIfAbsent(
                assistantId,
                id -> assistantRepository.findById(id).map(Assistant::getName).orElse(null));
    }

    /**
     * Resolve the caller's accessible projects (intersected with the optional
     * {@code projectIds} filter) and eagerly load each project's workflows and
     * assistants once, so the per-tab roll-ups never re-query the project set.
     */
    private Scope resolveScope(Authentication authentication, List<UUID> projectIds) {
        boolean filtered = projectIds != null && !projectIds.isEmpty();
        Map<UUID, Project> projects = new LinkedHashMap<>();
        List<Workflow> workflows = new ArrayList<>();
        List<Assistant> assistants = new ArrayList<>();
        for (Project p : projectRepository.findAll()) {
            if (filtered && !projectIds.contains(p.getId())) {
                continue;
            }
            if (!projectAccessEvaluator.canView(p.getId(), authentication)) {
                continue;
            }
            projects.put(p.getId(), p);
            workflows.addAll(workflowRepository.findByProjectId(p.getId()));
            assistants.addAll(assistantRepository.findByProjectIdOrderByNameAsc(p.getId()));
        }
        return new Scope(projects, workflows, assistants);
    }

    private static boolean isActive(WorkflowRequest r) {
        return r.getStatus() == RequestStatus.PENDING || r.getStatus() == RequestStatus.RUNNING;
    }

    private static Instant sessionTimestamp(Conversation c) {
        return c.getUpdatedAt();
    }

    private static String normalize(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean matches(String needle, String... fields) {
        if (needle == null) {
            return true;
        }
        for (String f : fields) {
            if (f != null && f.toLowerCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Immutable per-request snapshot of the caller's project scope and its services. */
    private record Scope(Map<UUID, Project> projects, List<Workflow> workflows, List<Assistant> assistants) {

        boolean contains(UUID projectId) {
            return projects.containsKey(projectId);
        }

        String projectName(UUID projectId) {
            Project p = projects.get(projectId);
            return p == null ? null : p.getName();
        }

        Collection<Project> projectValues() {
            return projects.values();
        }
    }
}
