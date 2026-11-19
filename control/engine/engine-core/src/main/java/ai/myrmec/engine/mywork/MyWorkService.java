package ai.myrmec.engine.mywork;

import ai.myrmec.engine._system.security.TeamAccessEvaluator;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.assistant.AssistantVersionRepository;
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
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamRepository;
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
 * <p>My Work is a personal, cross-Team view: it never owns data and never
 * writes. Every query starts from the set of projects the caller can
 * {@code VIEW} (resolved here, because {@code GET /api/v1/teams} is
 * unscoped), optionally narrowed by a {@code teamIds} chip filter, and then
 * rolls up the four tabs — Workflows, Conversations (Assistants), Approvals,
 * and Archived. Approval and archive <em>actions</em> are deliberately absent;
 * the UI drives those through the existing per-resource write endpoints so
 * there is exactly one authorization path per mutation.</p>
 *
 * <p>Scale assumption is admin-grade (the same one the Service-Type registry
 * makes): the Team set is small enough to filter in memory. The heavier
 * fan-outs (per-workflow executions, per-assistant sessions) are bounded by the
 * scoped Team set.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyWorkService {

    private final TeamRepository teamRepository;
    private final TeamAccessEvaluator teamAccessEvaluator;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRequestRepository workflowRequestRepository;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final AssistantRepository assistantRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;
    private final AssistantVersionRepository assistantVersionRepository;

    // ------------------------------------------------------------------ summary

    public MyWorkSummaryResponse summary(Authentication authentication, List<UUID> teamIds) {
        Scope scope = resolveScope(authentication, teamIds);

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
            Authentication authentication, List<UUID> teamIds, WorkflowStatus status, String q) {
        Scope scope = resolveScope(authentication, teamIds);
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
            String teamName = scope.teamName(w.getTeam().getId());
            rows.add(MyWorkflowRow.of(w, teamName, workflowRequestRepository.findByWorkflowId(w.getId())));
        }
        rows.sort(Comparator.comparing(MyWorkflowRow::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    // ------------------------------------------------------------ conversations

    public List<MyAssistantRow> conversations(Authentication authentication, List<UUID> teamIds, String q) {
        Scope scope = resolveScope(authentication, teamIds);
        Map<UUID, List<Conversation>> activeByAssistant = activeSessionsByAssistant(scope);
        Map<UUID, UUID> profileByAssistant = assistantProfileIds(scope.assistants());
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
            rows.add(MyAssistantRow.of(a, scope.teamName(a.getTeamId()), profileByAssistant.get(a.getId()), sessions.size(), lastSessionAt));
        }
        rows.sort(Comparator.comparing(MyAssistantRow::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** The signed-in user's own recently-active sessions, for the Continue rail (newest first, capped). */
    public List<MyContinueRow> continueRail(Authentication authentication, UUID userId, List<UUID> teamIds, int limit) {
        Scope scope = resolveScope(authentication, teamIds);
        Map<UUID, String> assistantNames = new HashMap<>();
        return conversationRepository.findByCreatedByOrderByUpdatedAtDesc(userId).stream()
                .filter(c -> c.getStatus() == Conversation.Status.ACTIVE)
                .filter(c -> scope.contains(c.getTeamId()))
                .limit(Math.max(0, limit))
                .map(c -> MyContinueRow.of(c, assistantName(c.getAssistantId(), assistantNames)))
                .toList();
    }

    // ------------------------------------------------------------------ approvals

    public List<MyApprovalRow> approvals(Authentication authentication, List<UUID> teamIds, String q) {
        Scope scope = resolveScope(authentication, teamIds);
        String needle = normalize(q);
        return pendingApprovalRows(scope, callerIdOf(authentication)).stream()
                .filter(r -> matches(needle, r.summary(), r.teamName()))
                // Soonest-expiring first; rows without an expiry sort last.
                .sorted(Comparator.comparing(
                        MyApprovalRow::expiresAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    // ------------------------------------------------------------------ archived

    public List<MyArchivedRow> archived(
            Authentication authentication, List<UUID> teamIds, MyArchivedRow.Type type, String q) {
        Scope scope = resolveScope(authentication, teamIds);
        String needle = normalize(q);
        List<MyArchivedRow> rows = new ArrayList<>();
        if (type == null || type == MyArchivedRow.Type.WORKFLOW) {
            for (Workflow w : scope.workflows()) {
                if (w.getStatus() == WorkflowStatus.ARCHIVED && matches(needle, w.getName(), w.getDescription())) {
                    rows.add(MyArchivedRow.ofWorkflow(w, scope.teamName(w.getTeam().getId())));
                }
            }
        }
        if (type == null || type == MyArchivedRow.Type.ASSISTANT) {
            for (Assistant a : scope.assistants()) {
                if (a.getArchivedAt() != null && matches(needle, a.getName(), a.getDescription())) {
                    rows.add(MyArchivedRow.ofAssistant(a, scope.teamName(a.getTeamId())));
                }
            }
        }
        // Most-recently archived first; rows without a timestamp sort last.
        rows.sort(Comparator.comparing(
                MyArchivedRow::archivedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    // -------------------------------------------------------------- internals

    /** All still-PENDING approval requests (conversation + execution sources) whose Team is in scope, as rows.
     *
     * <p>§17.4 approver targeting (workflows only): an orchestration /
     * workflow-execution approval is decided by exactly one human — the
     * triggering user ({@code WorkflowRequest.createdBy}). The tab narrows
     * those rows to {@code createdBy = caller}; conversation approvals
     * keep their interactive-session model (any Team member sees the
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
                if (conversation == null || !scope.contains(conversation.getTeamId())) {
                    continue;
                }
                rows.add(MyApprovalRow.fromConversation(msg, conversation, scope.teamName(conversation.getTeamId())));
            }
        }
        
        // Query execution-source approvals (WorkflowTask with approval_status=PENDING).
        // §17.4: workflow-sourced requests narrow to the triggering user —
        // decisions from any other user are rejected under the task row
        // lock, so the tab never surfaces an undecidable request.
        List<WorkflowTask> pendingExecutions = workflowTaskRepository.findByApprovalStatus("PENDING");
        if (!pendingExecutions.isEmpty()) {
            for (WorkflowTask task : pendingExecutions) {
                // Navigate to Team via request → workflow → Team
                WorkflowRequest request = task.getRequest();
                if (request == null) continue;
                Workflow workflow = request.getWorkflow();
                if (workflow == null) continue;
                UUID teamId = workflow.getTeam().getId();
                if (!scope.contains(teamId)) {
                    continue;
                }
                UUID triggerer = request.getCreatedBy() != null
                        ? request.getCreatedBy().getId() : null;
                if (callerId == null || triggerer == null || !triggerer.equals(callerId)) {
                    continue;
                }
                rows.add(MyApprovalRow.fromExecution(task, scope.teamName(teamId)));
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
        for (Team t : scope.teamValues()) {
            for (Conversation c : conversationRepository.findByTeamIdOrderByUpdatedAtDesc(t.getId())) {
                if (c.getStatus() != Conversation.Status.ACTIVE || c.getAssistantId() == null) {
                    continue;
                }
                byAssistant.computeIfAbsent(c.getAssistantId(), k -> new ArrayList<>()).add(c);
            }
        }
        return byAssistant;
    }

    private boolean canCreateServiceOfType(Authentication authentication, Scope scope, ServiceType type) {
        for (Team t : scope.teamValues()) {
            if (teamAccessEvaluator.canEdit(t.getId(), authentication)
                    && teamAccessEvaluator.allowsServiceType(t.getId(), type.name())) {
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
     * Map each assistant to the {@code agentProfileId} of its currently-published
     * version. Draft assistants have no profile id until first publish.
     */
    private Map<UUID, UUID> assistantProfileIds(List<Assistant> assistants) {
        Map<UUID, UUID> result = new HashMap<>();
        for (Assistant a : assistants) {
            if (a.getCurrentVersionId() == null) {
                continue;
            }
            assistantVersionRepository.findById(a.getCurrentVersionId())
                    .map(ai.myrmec.engine.assistant.AssistantVersion::getAgentProfileId)
                    .ifPresent(profileId -> result.put(a.getId(), profileId));
        }
        return result;
    }

    /**
     * Resolve the caller's accessible projects (intersected with the optional
     * {@code teamIds} filter) and eagerly load each Team's workflows and
     * assistants once, so the per-tab roll-ups never re-query the Team set.
     */
    private Scope resolveScope(Authentication authentication, List<UUID> teamIds) {
        boolean filtered = teamIds != null && !teamIds.isEmpty();
        Map<UUID, Team> teams = new LinkedHashMap<>();
        List<Workflow> workflows = new ArrayList<>();
        List<Assistant> assistants = new ArrayList<>();
        for (Team t : teamRepository.findAll()) {
            if (filtered && !teamIds.contains(t.getId())) {
                continue;
            }
            if (!teamAccessEvaluator.canView(t.getId(), authentication)) {
                continue;
            }
            teams.put(t.getId(), t);
            workflows.addAll(workflowRepository.findByTeamId(t.getId()));
            assistants.addAll(assistantRepository.findByTeamIdOrderByNameAsc(t.getId()));
        }
        return new Scope(teams, workflows, assistants);
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

    /** Immutable per-request snapshot of the caller's Team scope and its services. */
    private record Scope(Map<UUID, Team> teams, List<Workflow> workflows, List<Assistant> assistants) {

        boolean contains(UUID teamId) {
            return teams.containsKey(teamId);
        }

        String teamName(UUID teamId) {
            Team t = teams.get(teamId);
            return t == null ? null : t.getName();
        }

        Collection<Team> teamValues() {
            return teams.values();
        }
    }
}
