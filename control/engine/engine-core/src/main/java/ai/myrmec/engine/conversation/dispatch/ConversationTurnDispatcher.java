// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.agent.AgentHostService;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.node.NodeRegistryService;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.websocket.ConversationSocketRegistry;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import ai.myrmec.engine.websocket.message.payload.AgentBindPayload;
import ai.myrmec.engine.websocket.message.payload.ConversationTurnAssignPayload;
import ai.myrmec.engine.websocket.message.payload.InferenceAssignPayload;
import ai.myrmec.engine.websocket.message.payload.InferenceCancelPayload;
import ai.myrmec.engine.websocket.message.payload.SessionClosePayload;
import ai.myrmec.engine.websocket.message.payload.SessionOpenPayload;
import ai.myrmec.engine.websocket.message.payload.TaskAssignPayload;
import ai.myrmec.engine.inference.InferenceRequestAssembler;
import ai.myrmec.engine.inference.InferenceRequestSpec;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.inference.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Routes a freshly-arrived USER message to an idle agent instance as a
 * {@code conversation.turn.assign} frame (Phase 6d).
 *
 * <p>This dispatcher deliberately bypasses {@code WorkflowTask} and the
 * scheduled {@code TaskDispatcherService} poller: a chat turn is not a
 * workflow step, has no retry semantics, and needs to land within
 * sub-second latency for streaming to feel responsive. Polling adds
 * 2&nbsp;seconds of dead time on average and would force conversational
 * turns into a foreign data model (workflow / step / attempt) that
 * doesn't fit them.</p>
 *
 * <p>Context assembly here is intentionally simple for Phase 6d
 * (sliding window of the most-recent {@value #HISTORY_LIMIT} messages,
 * pinned facts pass-through, no summarisation). A summariser running
 * against a cheap-model handle is the natural Phase 6d follow-up but
 * shipping that without first proving the end-to-end dispatch path is
 * premature.</p>
 *
 * <p><b>Best-effort.</b> When no idle agent instance is available the
 * dispatcher logs a warning and returns {@code false} \u2014 it does not
 * queue. The conversation row still carries the USER message, and a
 * later turn (or an agent coming online) will pick the thread back up
 * once we add a backlog drainer. Failing loud here is the right call:
 * the user-WS replay surface (Phase 6c-2) shows them the un-answered
 * USER row.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationTurnDispatcher {

    /**
     * How many recent messages to ship as context (oldest first). Chosen
     * small for Phase 6d so token cost stays bounded; the future
     * summariser pass collapses anything older into a single SYSTEM
     * entry.
     */
    public static final int HISTORY_LIMIT = 20;

    /** Per-turn timeout shipped to the agent. Mirrors workflow tasks. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final AgentHostRepository agentRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final AgentRepository agentInstanceRepository;
    private final AgentConnectionManager connectionManager;
    private final AgentWebSocketHandler webSocketHandler;
    private final ModelService modelService;
    private final ai.myrmec.engine.snapshot.SnapshotWriter snapshotWriter;
    private final QuotaPolicyEngine quotaPolicyEngine;
    private final AgentHostService agentService;
    private final NodeRegistryService nodeRegistry;
    private final PendingTurnRegistry pendingTurnRegistry;
    private final ConversationSocketRegistry conversationSocketRegistry;
    private final ai.myrmec.engine.attachment.AttachmentService attachmentService;
    private final ai.myrmec.engine.setting.SystemSettingService systemSettingService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ai.myrmec.engine.conversation.ConversationNoticeService conversationNoticeService;
    private final SessionContextAssembler sessionContextAssembler;
    private final InferenceRequestAssembler inferenceRequestAssembler;
    private final ai.myrmec.engine.governance.GovernancePolicyResolver governancePolicyResolver;

    /**
     * Relay a cancel for the in-flight turn of a conversation to its bound
     * worker over the conversation socket. The worker breaks out of its
     * streaming / tool loop and acknowledges with {@code task.cancelled}
     * (handled by {@code ConversationInboundService.onTaskCancelled}, which
     * persists any partial assistant text and releases the worker).
     *
     * <p>Best-effort and node-local: the conversation socket lives only on
     * the replica the worker dialed, so this returns {@code false} when no
     * socket is attached on this replica (no in-flight turn to cancel, or
     * the turn is bound to a different node).</p>
     *
     * @return {@code true} if a cancel frame was delivered to a worker
     */
    public boolean cancel(UUID conversationId) {
        // Find the active session for this conversation
        Session session = sessionContextAssembler.findActiveSession(conversationId, "CONVERSATION");
        UUID sessionId = session != null ? session.getId() : conversationId;
        InferenceCancelPayload payload = new InferenceCancelPayload(
                conversationId,  // requestId — the turn being cancelled
                sessionId);
        boolean delivered = conversationSocketRegistry.sendMessage(
                conversationId,
                WebSocketMessage.of(MessageType.INFERENCE_CANCEL, payload));
        if (delivered) {
            log.info("Relayed conversation.turn.cancel for conv {}", conversationId);
        } else {
            log.debug("No conversation socket attached for conv {} \u2014 nothing to cancel", conversationId);
        }
        return delivered;
    }

    /**
     * Assemble + dispatch one turn. Returns {@code true} if a frame was
     * actually sent to an agent.
     */
    public boolean dispatch(UUID conversationId) {
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            log.warn("Cannot dispatch turn \u2014 conversation {} not found", conversationId);
            return false;
        }
        Conversation conversation = convOpt.get();

        if (conversation.getAgentId() == null) {
            log.warn("Cannot dispatch turn \u2014 conversation {} has no agentId pinned", conversationId);
            return false;
        }

        // Phase 8c: pre-flight quota check. We charge 0 tokens (the LLM
        // hasn't run yet) and rely on prior consumption to drive the block.
        // A hard block raises QuotaExceededException which the REST layer
        // translates to 429; for WS we just refuse to dispatch and let the
        // client surface the error.
        //
        // When the conversation has a pinned assistant, check at SERVICE
        // scope (scopeId = assistantId). check(SERVICE, assistantId, ...)
        // internally walks SERVICE -> PROJECT -> GROUP -> ORG via
        // buildScopeChain and returns the most restrictive decision. This
        // means a SERVICE RESERVATION block takes priority over the GROUP
        // ceiling -- the 429 will carry scopeHit=SERVICE.
        //
        // For legacy conversations with no assistant, fall back to PROJECT
        // scope (scopeId = projectId).
        UUID dispatchProjectId = conversation.getProjectId();
        UUID assistantId = conversation.getAssistantId();
        if (dispatchProjectId != null || assistantId != null) {
            QuotaDecision decision;
            UUID effectiveScopeId;
            if (assistantId != null) {
                decision = quotaPolicyEngine.check(
                        QuotaScope.SERVICE, assistantId,
                        QuotaResourceType.TOKENS, 0L);
                effectiveScopeId = assistantId;
            } else {
                decision = quotaPolicyEngine.check(
                        QuotaScope.PROJECT, dispatchProjectId,
                        QuotaResourceType.TOKENS, 0L);
                effectiveScopeId = dispatchProjectId;
            }
            if (decision.isBlocked()) {
                log.warn("Conversation turn blocked by quota -- conv {} scopeId {} (used {} of {})",
                        conversationId, effectiveScopeId,
                        decision.getConsumedAmount(), decision.getLimitAmount());
                throw new ai.myrmec.engine._system.exception.QuotaExceededException(
                        decision.getScopeHit() != null ? decision.getScopeHit() : QuotaScope.PROJECT,
                        effectiveScopeId,
                        QuotaResourceType.TOKENS,
                        decision.getLimitAmount(),
                        decision.getConsumedAmount());
            }
        }

        Optional<AgentHost> agentOpt = agentRepository.findById(conversation.getAgentId());
        if (agentOpt.isEmpty()) {
            log.warn("Cannot dispatch turn \u2014 agent {} not found for conv {}",
                    conversation.getAgentId(), conversationId);
            return false;
        }
        AgentHost agent = agentOpt.get();

        Optional<AgentProfile> profileOpt = agentProfileRepository.findByIdWithTools(agent.getProfileId());
        if (profileOpt.isEmpty()) {
            log.warn("Cannot dispatch turn \u2014 profile {} not found for conv {}",
                    agent.getProfileId(), conversationId);
            return false;
        }
        AgentProfile profile = profileOpt.get();

        // §9.5 sticky binding — if a worker is already BOUND to this
        // conversation (the previous turn kept it alive), skip the
        // reserve/bind/attach dance and flush the turn directly over the
        // existing conversation socket. This is the hot path between turns
        // in an active session: no socket churn, no re-attach, no H2
        // contention.
        Optional<Agent> boundOpt = agentInstanceRepository
                .findByConversationIdAndStatus(conversationId, Agent.Status.BOUND);
        if (boundOpt.isPresent()) {
            Agent boundInstance = boundOpt.get();
            if (conversationSocketRegistry.isAttached(conversationId)) {
                log.debug("Reusing BOUND worker {} for conv {} (sticky dispatch)",
                        boundInstance.getId(), conversationId);
                return dispatchOverBoundSocket(conversation, agent, profile,
                        boundInstance, conversationId);
            }
            // Worker is BOUND but the socket dropped — release it and fall
            // through to the cold-reserve path so a fresh socket is opened.
            log.info("BOUND worker {} for conv {} has no live socket — releasing for re-reserve",
                    boundInstance.getId(), conversationId);
            agentService.releaseInstance(boundInstance.getId());
        }

        // Slice 3b — atomically reserve a warm worker (IDLE → RESERVED),
        // pinning the conversation + profile version at reserve-time
        // (agent-concurrency §9.5). The compare-and-set inside
        // reserveIdleInstance prevents two turns from double-booking the
        // same worker; a lost race is indistinguishable from "no capacity".
        UUID profileVersionId = profile.getId();
        Agent idleInstance = reserveIdleInstance(agent.getId(), conversationId, profileVersionId)
                .orElse(null);
        if (idleInstance == null) {
            log.warn("No idle agent instance available for agent {} (conv {})",
                    agent.getId(), conversationId);
            // #86 — don't silently drop the turn. Surface a one-time SYSTEM
            // notice so the user knows their message was received and will be
            // answered once a worker comes online; the #87 backlog drainer
            // re-dispatches this conversation on the next agent connect.
            conversationNoticeService.emitNoAgentNotice(conversationId);
            return false;
        }

        // Tell the worker which conversation + profile version it now serves,
        // and which home node (this replica) to open its conversation socket
        // to. The worker acts on the binding by dialing homeNodeAddr and
        // sending conversation.attach (agent-concurrency §9.4); on a single
        // node the home node resolves to self.
        webSocketHandler.sendAgentBind(idleInstance.getId(),
                AgentBindPayload.builder()
                        .conversationId(conversationId)
                        .profileVersionId(profileVersionId)
                        .homeNodeId(nodeRegistry.getSelfNodeId())
                        .homeNodeAddr(nodeRegistry.getSelfAddress())
                        .build());

        List<ConversationMessage> all = conversationService.listMessages(conversationId);
        // #104b — assemble context from the active branch only; edit-resend /
        // regenerate soft-supersede the replaced rows, which must not feed the
        // next turn. The assistant sequence number still advances past the
        // global max (superseded rows keep their slots) so it never collides.
        List<ConversationMessage> active = all.stream()
                .filter(m -> !m.isSuperseded())
                // #86 — drop engine no-agent notices: a "no agent online" line
                // must never be shipped to the agent that picks the turn up.
                .filter(m -> !conversationNoticeService.isNoAgentNotice(m))
                .toList();
        List<ConversationTurnAssignPayload.HistoryEntry> historyEntries = buildSlidingWindow(active);
        String userMessage = lastUserContent(active).orElse("");
        long assistantSequenceNo = all.isEmpty()
                ? 0L
                : all.get(all.size() - 1).getSequenceNo() + 1;

        String systemPrompt = conversation.getSystemPromptOverride() != null
                ? conversation.getSystemPromptOverride()
                : profile.getSystemPrompt();

        // Assemble session.open (sent once when worker binds)
        SessionOpenPayload sessionOpen = sessionContextAssembler.assemble(
                "CONVERSATION", conversationId, conversation.getProjectId(),
                agent.getProfileId());

        // Build the inference request spec for the conversation transcript composer
        List<InferenceRequestSpec.HistoryEntry> history = historyEntries.stream()
                .map(h -> new InferenceRequestSpec.HistoryEntry(h.getRole(), h.getContent()))
                .toList();
        List<InferenceRequestSpec.AttachmentDescriptor> attachments =
                buildAttachments(conversationId, active, profile).stream()
                        .map(a -> new InferenceRequestSpec.AttachmentDescriptor(
                                a.getId().toString(), a.getFilename(), a.getMediaType(), a.getSizeBytes(),
                                a.getInlineText(), a.isImage(), a.getReadContentPath()))
                        .toList();

        // Extract tool names from the session for this turn
        List<String> activeToolNames = sessionOpen.tools() != null
                ? sessionOpen.tools().stream()
                        .map(SessionOpenPayload.ToolDefinition::name)
                        .toList()
                : List.of();

        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .sessionId(sessionOpen.sessionId())
                .requestId(conversationId)  // conversation turn = conversationId for now
                .projectId(conversation.getProjectId())
                .sequenceNo(assistantSequenceNo)
                .governanceProfileCode(governancePolicyResolver.resolveOrgDefault().code())
                .contextSnapshot(conversation.getContextSnapshot())
                .contextPinning(conversation.getContextSnapshot() != null ? "PINNED_AT_START" : null)
                .conversationSystemPrompt(systemPrompt)
                .pinnedFacts(conversation.getPinnedFacts())
                .history(history)
                .userMessage(userMessage)
                .attachments(attachments)
                .activeToolNames(activeToolNames)
                .build();

        InferenceAssignPayload payload = inferenceRequestAssembler.assemble(spec);

        // Buffer both session.open and inference.assign until the worker
        // opens + attaches its conversation socket (agent-concurrency §9.4).
        // The conversation-socket handler flushes them on attach.
        // CRITICAL: enqueue session.open BEFORE inference.assign. The
        // handleAttach handler runs on a different thread and calls
        // takeSessionOpen() before take(). If enqueue(inference.assign)
        // runs first, a concurrent handleAttach can observe the
        // inference.assign but miss the session.open, causing the agent
        // to receive inference.assign without session.open first.
        pendingTurnRegistry.enqueueSessionOpen(conversationId, sessionOpen);
        pendingTurnRegistry.enqueue(conversationId, payload);
        log.info("Buffered inference turn for agent instance {} (conv {} seq {}) — awaiting conversation socket attach",
                idleInstance.getId(), conversationId, assistantSequenceNo);

        // Race-condition fix: if the conversation socket already attached
        // (handleAttach ran before this dispatch), the buffered frames would
        // sit in the registry forever. Flush them now if the socket is open.
        if (conversationSocketRegistry.isAttached(conversationId)) {
            pendingTurnRegistry.takeSessionOpen(conversationId).ifPresent(so -> {
                conversationSocketRegistry.sendMessage(conversationId,
                        WebSocketMessage.of(MessageType.SESSION_OPEN, so));
                log.info("Flushed session.open (late) to agent {} over conversation socket (conv {} session {})",
                        idleInstance.getId(), conversationId, so.sessionId());
            });
            pendingTurnRegistry.take(conversationId).ifPresent(turn -> {
                boolean delivered = conversationSocketRegistry.sendMessage(conversationId,
                        WebSocketMessage.of(MessageType.INFERENCE_ASSIGN, turn));
                if (delivered) {
                    log.info("Flushed inference.assign (late) to agent {} over conversation socket (conv {})",
                            idleInstance.getId(), conversationId);
                }
            });
        }

        // Phase 9a — archive the dispatched payload so V2 replay has
        // the same inputs the agent saw. Best-effort: never abort
        // the caller on a snapshot failure.
        snapshotWriter.write(ai.myrmec.engine.snapshot.SnapshotWriter.SnapshotRequest.builder()
                .projectId(conversation.getProjectId())
                .eventType("CONVERSATION_TURN_DISPATCHED")
                .agentId(agent.getId())
                .conversationId(conversationId)
                .source(conversation.getSource() == null ? null : conversation.getSource().name())
                .serviceAccountId(conversation.getServiceAccountId())
                .externalUserRef(conversation.getExternalUserRef())
                .userId(conversation.getCreatedBy())
                .payload(payload)
                .build());
        return true;
    }

    /**
     * Flush a turn directly over an already-BOUND worker's conversation
     * socket (§9.5 sticky binding hot path). Builds the same payload as the
     * cold-reserve path but sends it immediately — no reserve, no bind,
     * no attach, no PendingTurnRegistry.
     */
    private boolean dispatchOverBoundSocket(Conversation conversation, AgentHost agent,
                                             AgentProfile profile, Agent boundInstance,
                                             UUID conversationId) {
        List<ConversationMessage> all = conversationService.listMessages(conversationId);
        List<ConversationMessage> active = all.stream()
                .filter(m -> !m.isSuperseded())
                .filter(m -> !conversationNoticeService.isNoAgentNotice(m))
                .toList();
        List<ConversationTurnAssignPayload.HistoryEntry> historyEntries = buildSlidingWindow(active);
        String userMessage = lastUserContent(active).orElse("");
        long assistantSequenceNo = all.isEmpty() ? 0L : all.get(all.size() - 1).getSequenceNo() + 1;
        String systemPrompt = conversation.getSystemPromptOverride() != null
                ? conversation.getSystemPromptOverride()
                : profile.getSystemPrompt();

        // Find existing session for this conversation (sticky path — session already open)
        Session session = sessionContextAssembler.findActiveSession(conversationId, "CONVERSATION");
        UUID sessionId = session != null ? session.getId() : conversationId;

        // Resolve tool names from the agent profile (sticky path — no session.open)
        List<String> activeToolNames = resolveToolNames(profile);

        // Build the inference request spec
        List<InferenceRequestSpec.HistoryEntry> history = historyEntries.stream()
                .map(h -> new InferenceRequestSpec.HistoryEntry(h.getRole(), h.getContent()))
                .toList();
        List<InferenceRequestSpec.AttachmentDescriptor> attachments =
                buildAttachments(conversationId, active, profile).stream()
                        .map(a -> new InferenceRequestSpec.AttachmentDescriptor(
                                a.getId().toString(), a.getFilename(), a.getMediaType(), a.getSizeBytes(),
                                a.getInlineText(), a.isImage(), a.getReadContentPath()))
                        .toList();

        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .sessionId(sessionId)
                .requestId(conversationId)
                .projectId(conversation.getProjectId())
                .sequenceNo(assistantSequenceNo)
                .governanceProfileCode(governancePolicyResolver.resolveOrgDefault().code())
                .contextSnapshot(conversation.getContextSnapshot())
                .contextPinning(conversation.getContextSnapshot() != null ? "PINNED_AT_START" : null)
                .conversationSystemPrompt(systemPrompt)
                .pinnedFacts(conversation.getPinnedFacts())
                .history(history)
                .userMessage(userMessage)
                .attachments(attachments)
                .activeToolNames(activeToolNames)
                .build();

        InferenceAssignPayload payload = inferenceRequestAssembler.assemble(spec);

        boolean delivered = conversationSocketRegistry.sendMessage(conversationId,
                WebSocketMessage.of(MessageType.INFERENCE_ASSIGN, payload));
        if (delivered) {
            log.info("Flushed sticky turn to BOUND worker {} over conversation socket (conv {} seq {})",
                    boundInstance.getId(), conversationId, assistantSequenceNo);
            // Best-effort snapshot.
            snapshotWriter.write(ai.myrmec.engine.snapshot.SnapshotWriter.SnapshotRequest.builder()
                    .projectId(conversation.getProjectId())
                    .eventType("CONVERSATION_TURN_DISPATCHED")
                    .agentId(agent.getId())
                    .conversationId(conversationId)
                    .source(conversation.getSource() == null ? null : conversation.getSource().name())
                    .serviceAccountId(conversation.getServiceAccountId())
                    .externalUserRef(conversation.getExternalUserRef())
                    .userId(conversation.getCreatedBy())
                    .payload(payload)
                    .build());
        } else {
            log.warn("Sticky dispatch failed — conversation socket vanished for conv {}; releasing worker",
                    conversationId);
            agentService.releaseInstance(boundInstance.getId());
        }
        return delivered;
    }

    /** Setting key for the summariser model code (empty = fall back to the conversation's model). */
    private static final String SUMMARIZER_MODEL_CODE_KEY = "summarizer_model_code";

    /** System prompt that puts the agent into faithful-summariser mode for a SUMMARY turn. */
    private static final String SUMMARISER_SYSTEM_PROMPT =
            "You are a summarisation assistant. Produce a faithful, concise running summary of the "
            + "conversation so far that preserves key facts, decisions, open questions, and stated user "
            + "preferences. Do not invent information and do not answer as the assistant \u2014 output only the "
            + "summary text.";

    /** The user-turn instruction shipped on a SUMMARY turn. */
    private static final String SUMMARISER_USER_INSTRUCTION =
            "Summarise the conversation so far into a single concise running summary, incorporating any "
            + "earlier summary and the messages above.";

    /**
     * Dispatch an engine-orchestrated summarisation turn (#8). Reuses the
     * normal reserve&nbsp;&rarr;&nbsp;bind&nbsp;&rarr;&nbsp;enqueue path so the
     * summary is produced by the conversation's agent over the same conversation
     * socket; the only differences are the {@code purpose = "SUMMARY"} marker,
     * the summariser system prompt + model, and that the history is the block of
     * older turns being folded (plus any earlier summary) rather than the live
     * window.
     *
     * <p>Caller ({@code ConversationSummaryService}) records the in-flight marker
     * <em>before</em> calling so the eventual completion is routed into a
     * {@code CONTEXT_SUMMARY} row; this method returns {@code false} when no warm
     * worker could be reserved so the caller can release that marker and retry
     * later.</p>
     *
     * @param previousSummaryContent the prior summary's body to carry forward, or null
     * @param olderBlock             the older turns to fold, oldest first (non-empty)
     * @return {@code true} if a summary turn was buffered for a reserved worker
     */
    public boolean dispatchSummary(UUID conversationId,
                                   String previousSummaryContent,
                                   List<ConversationMessage> olderBlock) {
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            log.warn("Cannot dispatch summary \u2014 conversation {} not found", conversationId);
            return false;
        }
        Conversation conversation = convOpt.get();
        if (conversation.getAgentId() == null) {
            log.warn("Cannot dispatch summary \u2014 conversation {} has no agentId pinned", conversationId);
            return false;
        }

        Optional<AgentHost> agentOpt = agentRepository.findById(conversation.getAgentId());
        if (agentOpt.isEmpty()) {
            log.warn("Cannot dispatch summary \u2014 agent {} not found for conv {}",
                    conversation.getAgentId(), conversationId);
            return false;
        }
        AgentHost agent = agentOpt.get();

        Optional<AgentProfile> profileOpt = agentProfileRepository.findByIdWithTools(agent.getProfileId());
        if (profileOpt.isEmpty()) {
            log.warn("Cannot dispatch summary \u2014 profile {} not found for conv {}",
                    agent.getProfileId(), conversationId);
            return false;
        }
        AgentProfile profile = profileOpt.get();

        UUID profileVersionId = profile.getId();
        Agent idleInstance = reserveIdleInstance(agent.getId(), conversationId, profileVersionId)
                .orElse(null);
        if (idleInstance == null) {
            log.info("No idle agent instance available to summarise conv {} \u2014 deferring", conversationId);
            return false;
        }

        webSocketHandler.sendAgentBind(idleInstance.getId(),
                AgentBindPayload.builder()
                        .conversationId(conversationId)
                        .profileVersionId(profileVersionId)
                        .homeNodeId(nodeRegistry.getSelfNodeId())
                        .homeNodeAddr(nodeRegistry.getSelfAddress())
                        .build());

        List<ConversationMessage> all = conversationService.listMessages(conversationId);
        long assistantSequenceNo = all.isEmpty()
                ? 0L
                : all.get(all.size() - 1).getSequenceNo() + 1;

        List<InferenceRequestSpec.HistoryEntry> history = new ArrayList<>();
        if (previousSummaryContent != null && !previousSummaryContent.isBlank()) {
            history.add(new InferenceRequestSpec.HistoryEntry(
                    ConversationMessage.Role.SYSTEM.name(),
                    SUMMARY_WIRE_PREFIX + previousSummaryContent));
        }
        for (ConversationMessage m : olderBlock) {
            history.add(new InferenceRequestSpec.HistoryEntry(
                    m.getRole().name(),
                    m.getContent()));
        }

        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .sessionId(conversationId)
                .requestId(conversationId)
                .projectId(conversation.getProjectId())
                .sequenceNo(assistantSequenceNo)
                .governanceProfileCode(governancePolicyResolver.resolveOrgDefault().code())
                .contextSnapshot(conversation.getContextSnapshot())
                .contextPinning(conversation.getContextSnapshot() != null ? "PINNED_AT_START" : null)
                .conversationSystemPrompt(SUMMARISER_SYSTEM_PROMPT)
                .pinnedFacts(null)
                .history(history)
                .userMessage(SUMMARISER_USER_INSTRUCTION)
                .attachments(Collections.emptyList())
                .build();

        InferenceAssignPayload payload = inferenceRequestAssembler.assemble(spec);

        pendingTurnRegistry.enqueue(conversationId, payload);
        log.info("Buffered summarisation turn for agent instance {} (conv {} folding {} msg(s))",
                idleInstance.getId(), conversationId, olderBlock.size());

        snapshotWriter.write(ai.myrmec.engine.snapshot.SnapshotWriter.SnapshotRequest.builder()
                .projectId(conversation.getProjectId())
                .eventType("CONVERSATION_SUMMARY_DISPATCHED")
                .agentId(agent.getId())
                .conversationId(conversationId)
                .source(conversation.getSource() == null ? null : conversation.getSource().name())
                .serviceAccountId(conversation.getServiceAccountId())
                .externalUserRef(conversation.getExternalUserRef())
                .userId(conversation.getCreatedBy())
                .payload(payload)
                .build());
        return true;
    }

    /**
     * Pick the first warm worker that is both connection-idle and wins the
     * atomic {@code IDLE → RESERVED} claim, pinning the conversation and
     * profile version. A worker that loses the compare-and-set (claimed by a
     * racing dispatch between the {@code findBy…} read and the update) is
     * skipped. Returns empty when no worker could be reserved.
     */
    private Optional<Agent> reserveIdleInstance(UUID agentId, UUID conversationId,
                                                UUID profileVersionId) {
        List<Agent> instances = agentInstanceRepository.findByAgentHostIdAndStatus(
                agentId, Agent.Status.IDLE);
        for (Agent instance : instances) {
            if (!connectionManager.isAgentIdle(instance.getId())) {
                continue;
            }
            // reserveInstance runs the atomic IDLE → RESERVED compare-and-set
            // inside its own short transaction (the @Modifying update needs one).
            if (agentService.reserveInstance(instance.getId(), conversationId, profileVersionId)) {
                return Optional.of(instance);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the most-recent {@link #HISTORY_LIMIT} messages, oldest
     * first. Always copies into an {@code ArrayList} so callers (and
     * Jackson) can iterate without surprises.
     *
     * <p>#8 — when the active branch carries a {@link
     * ConversationMessage.Role#CONTEXT_SUMMARY}, the messages it folded are
     * dropped and the summary itself anchors the window in their place. The
     * most-recent summary wins; older (superseded) summaries and the raw
     * turns they compacted never reach the agent. The summary ships on the
     * wire as a {@code SYSTEM} entry (the SDK only understands USER /
     * ASSISTANT / SYSTEM) with a short label so the model reads it as prior
     * context; the persisted row keeps its {@code CONTEXT_SUMMARY} role for
     * the transcript's transparency marker (#8a).</p>
     */
    /**
     * Resolve tool names from the agent profile for conversation turns.
     * Returns the tool codes of all ACTIVE tools assigned to the profile.
     */
    private List<String> resolveToolNames(AgentProfile profile) {
        var profileTools = profile.getTools();
        if (profileTools == null || profileTools.isEmpty()) {
            return List.of();
        }
        return profileTools.stream()
                .filter(t -> t.getStatus() == ai.myrmec.engine.tool.ToolStatus.ACTIVE)
                .map(ai.myrmec.engine.tool.Tool::getCode)
                .toList();
    }

    private List<ConversationTurnAssignPayload.HistoryEntry> buildSlidingWindow(
            List<ConversationMessage> all) {
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }

        ConversationMessage latestSummary = null;
        for (ConversationMessage m : all) {
            if (m.getRole() == ConversationMessage.Role.CONTEXT_SUMMARY
                    && (latestSummary == null
                        || m.getSequenceNo() > latestSummary.getSequenceNo())) {
                latestSummary = m;
            }
        }

        List<ConversationMessage> effective;
        if (latestSummary == null) {
            effective = all;
        } else {
            long coversUpTo = summaryCoverage(latestSummary);
            effective = new ArrayList<>(all.size());
            effective.add(latestSummary);
            for (ConversationMessage m : all) {
                if (m.getRole() == ConversationMessage.Role.CONTEXT_SUMMARY) {
                    continue; // latest already anchored; drop superseded summaries
                }
                if (m.getSequenceNo() <= coversUpTo) {
                    continue; // folded into the summary
                }
                effective.add(m);
            }
        }

        int from = Math.max(0, effective.size() - HISTORY_LIMIT);
        List<ConversationMessage> windowed =
                new ArrayList<>(effective.subList(from, effective.size()));
        // Always keep the summary anchor at the front, even if a large
        // post-summary tail would otherwise trim it out of the window.
        if (latestSummary != null && !windowed.contains(latestSummary)) {
            windowed.add(0, latestSummary);
        }

        List<ConversationTurnAssignPayload.HistoryEntry> out = new ArrayList<>(windowed.size());
        for (ConversationMessage m : windowed) {
            if (m.getRole() == ConversationMessage.Role.CONTEXT_SUMMARY) {
                out.add(ConversationTurnAssignPayload.HistoryEntry.builder()
                        .role(ConversationMessage.Role.SYSTEM.name())
                        .content(SUMMARY_WIRE_PREFIX
                                + (m.getContent() == null ? "" : m.getContent()))
                        .sequenceNo(m.getSequenceNo())
                        .build());
            } else {
                out.add(ConversationTurnAssignPayload.HistoryEntry.builder()
                        .role(m.getRole().name())
                        .content(m.getContent())
                        .sequenceNo(m.getSequenceNo())
                        .build());
            }
        }
        return out;
    }

    /** Label prefixed to a context summary's body on the wire so the model
     * reads it as prior context rather than a fresh instruction. */
    private static final String SUMMARY_WIRE_PREFIX = "[Summary of earlier conversation]\n";

    /**
     * The highest sequence number folded into the given summary. Reads the
     * authoritative {@code coversUpToSequenceNo} from the row's marker;
     * falls back to "everything strictly before the summary row" when the
     * marker is absent or unparseable.
     */
    private long summaryCoverage(ConversationMessage summary) {
        String json = summary.getPayloadJson();
        if (json != null && !json.isBlank()) {
            try {
                ai.myrmec.engine.conversation.ContextSummaryMarker marker =
                        objectMapper.readValue(json,
                                ai.myrmec.engine.conversation.ContextSummaryMarker.class);
                if (marker != null && marker.coversUpToSequenceNo() != null) {
                    return marker.coversUpToSequenceNo();
                }
            } catch (Exception e) {
                log.warn("Unparseable CONTEXT_SUMMARY marker on message {} (conv {}): {}",
                        summary.getId(), summary.getConversationId(), e.getMessage());
            }
        }
        return summary.getSequenceNo() - 1;
    }

    private Optional<String> lastUserContent(List<ConversationMessage> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            ConversationMessage m = all.get(i);
            if (m.getRole() == ConversationMessage.Role.USER) {
                return Optional.ofNullable(m.getContent());
            }
        }
        return Optional.empty();
    }

    /** Setting key for the max inline-injected text size, in estimated tokens. */
    private static final String INLINE_TOKEN_LIMIT_KEY = "attachment_inline_token_limit";
    private static final long INLINE_TOKEN_LIMIT_DEFAULT = 4000L;

    /**
     * Setting key for the maximum aggregate fraction of the per-turn context
     * token budget that inline attachment text may occupy across all
     * attachments on the turn (#103 Slice B). Read forgivingly via
     * {@link ai.myrmec.engine.setting.SystemSettingService#getRatio} and clamped
     * to {@code (0,1]} — a zero/out-of-range value falls back to the default.
     */
    private static final String INLINE_RATIO_MAX_KEY = "attachment_inline_ratio_max";
    private static final double INLINE_RATIO_MAX_DEFAULT = 0.5;

    /**
     * Setting key for the resolved per-turn context token budget that the
     * inline-ratio guard multiplies by {@link #INLINE_RATIO_MAX_KEY}. The
     * platform has no per-model {@code contextWindow} column today, so the
     * budget is a single tunable read forgivingly with a sensible default; the
     * default pairs with {@link #INLINE_RATIO_MAX_DEFAULT} to yield an aggregate
     * inline budget equal to one full {@link #INLINE_TOKEN_LIMIT_KEY} cap.
     */
    private static final String CONTEXT_TOKEN_BUDGET_KEY = "context_token_budget";
    private static final long CONTEXT_TOKEN_BUDGET_DEFAULT = 8000L;


    /**
     * Build the attachment descriptors for the turn from the clean rows
     * bound to the most-recent active USER message. Small text documents
     * are extracted inline (within the {@value #INLINE_TOKEN_LIMIT_KEY}
     * budget); images are flagged for native vision parts only when the
     * resolved model supports vision. Best-effort: any per-attachment
     * failure (e.g. a missing blob) is logged and that row is skipped so a
     * single bad file never blocks the turn.
     */
    private List<ConversationTurnAssignPayload.AttachmentDescriptor> buildAttachments(
            UUID conversationId, List<ConversationMessage> active, AgentProfile profile) {
        UUID userMessageId = null;
        for (int i = active.size() - 1; i >= 0; i--) {
            ConversationMessage m = active.get(i);
            if (m.getRole() == ConversationMessage.Role.USER) {
                userMessageId = m.getId();
                break;
            }
        }
        if (userMessageId == null) {
            return Collections.emptyList();
        }
        List<ai.myrmec.engine.attachment.ConversationMessageAttachment> rows =
                attachmentService.listForMessage(userMessageId);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        boolean supportsVision = resolveSupportsVision(profile);
        long inlineTokenLimit = systemSettingService.getInt(
                INLINE_TOKEN_LIMIT_KEY, INLINE_TOKEN_LIMIT_DEFAULT);
        // Slice B — aggregate inline-budget guard. The per-attachment size cap
        // above applies first; this second gate caps the *sum* of inlined text
        // across the turn at ratioMax × contextBudget so several within-cap
        // attachments cannot collectively crowd out conversation history.
        double ratioMax = systemSettingService.getRatio(
                INLINE_RATIO_MAX_KEY, INLINE_RATIO_MAX_DEFAULT);
        if (ratioMax <= 0.0 || ratioMax > 1.0) {
            // getRatio already rejects values outside [0,1]; this additionally
            // clamps the policy to (0,1] (a zero share would demote everything).
            ratioMax = INLINE_RATIO_MAX_DEFAULT;
        }
        long contextBudget = systemSettingService.getInt(
                CONTEXT_TOKEN_BUDGET_KEY, CONTEXT_TOKEN_BUDGET_DEFAULT);
        long aggregateInlineBudget = (long) Math.floor(ratioMax * contextBudget);
        long runningInlineTokens = 0L;
        List<ConversationTurnAssignPayload.AttachmentDescriptor> out = new ArrayList<>(rows.size());
        for (ai.myrmec.engine.attachment.ConversationMessageAttachment row : rows) {
            boolean isImage = row.getMediaType() != null
                    && row.getMediaType().startsWith("image/");
            InlineExtractionResult inline = InlineExtractionResult.none();
            if (!isImage && isTextLike(row.getMediaType())) {
                inline = extractInlineText(conversationId, row, inlineTokenLimit);
            }

            String inlineText = inline.inlineText();
            boolean omittedBySize = inline.omittedBySize();
            boolean omittedByBudget = false;
            // Ratio-budget gate runs only on text that already cleared the
            // per-attachment size cap. Greedy by upload order: once the running
            // inline-token total would exceed the aggregate budget, this and
            // every later attachment is demoted to read-on-demand.
            if (inlineText != null) {
                if (runningInlineTokens + inline.estimatedTokens() > aggregateInlineBudget) {
                    inlineText = null;
                    omittedByBudget = true;
                } else {
                    runningInlineTokens += inline.estimatedTokens();
                }
            }

            out.add(ConversationTurnAssignPayload.AttachmentDescriptor.builder()
                    .id(row.getId())
                    .filename(row.getFilename())
                    .mediaType(row.getMediaType())
                    .sizeBytes(row.getSizeBytes())
                    .sha256(row.getSha256())
                    .image(isImage && supportsVision)
                    .inlineText(inlineText)
                    .inlineTextOmittedBySize(omittedBySize)
                    .inlineTextOmittedByBudget(omittedByBudget)
                    .readContentPath(buildAgentAttachmentContentPath(conversationId, row.getId()))
                    .build());
        }
        return out;
    }

    private static String buildAgentAttachmentContentPath(UUID conversationId, UUID attachmentId) {
        return "/api/v1/agent/conversations/" + conversationId
                + "/attachments/" + attachmentId + "/content";
    }

    /** Whether the profile's default model is flagged vision-capable. */
    private boolean resolveSupportsVision(AgentProfile profile) {
        if (profile.getDefaultModel() == null) {
            return false;
        }
        try {
            return modelService.findByCode(profile.getDefaultModel()).isSupportsVision();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTextLike(String mediaType) {
        return mediaType != null
                && (mediaType.startsWith("text/")
                || mediaType.equals("application/json")
                || mediaType.equals("application/xml"));
    }

    /**
     * Decode a text attachment's bytes as UTF-8 and return them when the
     * estimated token count (~chars/4) fits the inline budget; otherwise
     * null so the agent fetches it on demand instead.
     */
    private InlineExtractionResult extractInlineText(
            UUID conversationId,
            ai.myrmec.engine.attachment.ConversationMessageAttachment row,
            long inlineTokenLimit) {
        try {
            byte[] bytes = attachmentService.download(conversationId, row.getId());
            long estimatedTokens = (bytes.length / 4L) + 1L;
            if (estimatedTokens > inlineTokenLimit) {
                return InlineExtractionResult.omittedBySizeLimit();
            }
            return InlineExtractionResult.inline(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                    estimatedTokens);
        } catch (Exception e) {
            log.warn("Could not extract inline text for attachment {} (conv {}): {}",
                    row.getId(), conversationId, e.getMessage());
            return InlineExtractionResult.none();
        }
    }

    /**
     * Outcome of a per-attachment inline extraction: the text (when it cleared
     * the per-attachment size cap), the reason it was withheld, and the
     * estimated token count the aggregate ratio guard sums against the turn's
     * inline budget.
     */
    private record InlineExtractionResult(String inlineText, boolean omittedBySize, long estimatedTokens) {
        static InlineExtractionResult inline(String inlineText, long estimatedTokens) {
            return new InlineExtractionResult(inlineText, false, estimatedTokens);
        }

        static InlineExtractionResult omittedBySizeLimit() {
            return new InlineExtractionResult(null, true, 0L);
        }

        static InlineExtractionResult none() {
            return new InlineExtractionResult(null, false, 0L);
        }
    }

    /**
     * Look up the model assigned to the profile and decrypt its API key.
     * Mirrors {@code TaskDispatcherService.buildTaskPayload} so behaviour
     * stays consistent between workflow tasks and conversational turns.
     */
    private TaskAssignPayload.ModelInfo resolveModel(AgentProfile profile) {
        if (profile.getDefaultModel() == null) {
            log.warn("Agent profile '{}' has no default model configured \u2014 dispatch without modelInfo",
                    profile.getName());
            return null;
        }
        return resolveModelByCode(profile.getDefaultModel());
    }

    /**
     * Build a {@link TaskAssignPayload.ModelInfo} for an explicit model code,
     * decrypting its API key. Mirrors {@code TaskDispatcherService.buildTaskPayload}
     * so behaviour stays consistent between workflow tasks and conversational
     * turns. Returns {@code null} (caller ships no modelInfo) when the code is
     * blank or cannot be resolved.
     */
    private TaskAssignPayload.ModelInfo resolveModelByCode(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            return null;
        }
        try {
            Model model = modelService.findByCode(modelCode);
            String apiKey = modelService.getApiKey(model.getCode());
            String apiEndpoint = model.getApiEndpoint();
            if (apiEndpoint == null && model.getProviderConfig() != null) {
                apiEndpoint = model.getProviderConfig().getBaseUrl();
            }
            return TaskAssignPayload.ModelInfo.builder()
                    .provider(model.getProvider())
                    .modelId(model.getModelId())
                    .apiEndpoint(apiEndpoint)
                    .apiKey(apiKey)
                    .parameters(model.getDefaultParams())
                    .build();
        } catch (Exception e) {
            log.warn("Could not resolve model '{}': {}", modelCode, e.getMessage());
            return null;
        }
    }
}
