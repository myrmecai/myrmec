// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentProfileVersionRepository;
import ai.myrmec.engine.agent.HostSelectionService;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.ExecutionCommandSender;
import ai.myrmec.engine.inference.execution.ExecutionInputAssembler;
import ai.myrmec.engine.inference.execution.ExecutionRegistry;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import ai.myrmec.engine.websocket.host.payload.ExecutionStartPayload;
import ai.myrmec.engine.inference.InferenceMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * Routes a freshly-arrived USER message onto the unified host-control socket
 * as a session&nbsp;&rarr;&nbsp;execution turn (protocol &sect;7/&sect;8).
 *
 * <p>This dispatcher deliberately bypasses {@code WorkflowTask} and the
 * scheduled {@code TaskDispatcherService} poller: a chat turn is not a
 * workflow step, has no retry semantics, and needs to land within
 * sub-second latency for streaming to feel responsive. Polling adds
 * 2&nbsp;seconds of dead time on average and would force conversational
 * turns into a foreign data model (workflow / step / attempt) that
 * doesn't fit them.</p>
 *
 * <p><b>One-way unified path.</b> The legacy {@code agent.bind} +
 * {@code conversation.attach} + {@code inference.assign} wire is gone. A turn
 * is staged one of two ways:</p>
 * <ol>
 *   <li><b>Reuse</b> &mdash; an ACTIVE {@code CONVERSATION} session already
 *       exists for this conversation and is pinned to the live host this
 *       dispatch selected: the SAME session row serves the next turn.
 *       {@link ExecutionRegistry#start} creates the execution row and
 *       {@link ExecutionCommandSender#startConversation} ships it. No wire
 *       handshake, no re-allocation.</li>
 *   <li><b>Offer</b> &mdash; no usable ACTIVE session:
 *       {@link SessionAllocator#offer} mints the session row (OFFERED) on the
 *       host selected by capacity, and
 *       {@link HostControlWebSocketHandler#sendSessionOffer} puts it on the
 *       wire. The assembled turn is parked in {@link PendingConversationTurns}
 *       because the host's {@code session.accept}/{@code session.opened}
 *       answers arrive <em>after</em> this method returns, and &sect;7.4
 *       forbids {@code execution.start} before {@code session.opened}.</li>
 * </ol>
 *
 * <p><b>Best-effort.</b> When no host with live capacity serves the project the
 * dispatcher logs a warning, emits the one-time #86 no-agent notice, and
 * returns {@code false} &mdash; it does not queue. The conversation row still
 * carries the USER message, and the #87 backlog drainer re-dispatches the
 * thread when a host comes online.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationTurnDispatcher {

    /** Service type / session kind for conversational sessions. */
    public static final String SERVICE_TYPE_CONVERSATION = "CONVERSATION";

    /**
     * How many recent messages the transcript composer ships as context
     * (oldest first). Kept small so token cost stays bounded; the summary pass
     * collapses anything older into a single SYSTEM entry.
     */
    public static final int HISTORY_LIMIT = 20;

    /** Per-turn timeout shipped to the agent. Mirrors workflow tasks. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** Cancellation reason code shipped on {@code execution.cancel} (&sect;8.8). */
    private static final String CANCEL_REASON_USER = "USER_CANCEL";

    /** Grace period the host gets to unwind a cancelled turn, in seconds. */
    private static final int CANCEL_GRACE_SECONDS = 5;

    /** Execution states that mean "a turn is currently in flight on this session". */
    private static final List<SessionExecution.State> IN_FLIGHT_STATES =
            List.of(SessionExecution.State.STARTING, SessionExecution.State.RUNNING,
                    SessionExecution.State.CANCELLING);

    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final AgentProfileRepository agentProfileRepository;
    private final AgentProfileVersionRepository agentProfileVersionRepository;
    private final QuotaPolicyEngine quotaPolicyEngine;
    private final ai.myrmec.engine.snapshot.SnapshotWriter snapshotWriter;
    private final ai.myrmec.engine.conversation.ConversationNoticeService conversationNoticeService;

    // ---- unified protocol seams (Â§7/Â§8) ----
    private final HostSelectionService hostSelectionService;
    private final AgentHostInstanceRepository hostInstanceRepository;
    private final SessionAllocator sessionAllocator;
    private final SessionRepository sessionRepository;
    private final SessionExecutionRepository executionRepository;
    private final ExecutionRegistry executionRegistry;
    private final ExecutionCommandSender executionCommandSender;
    private final ExecutionInputAssembler executionInputAssembler;
    private final HostControlWebSocketHandler hostControlWebSocketHandler;
    private final PendingConversationTurns pendingConversationTurns;

    /**
     * Relay a cancel for the in-flight turn of a conversation to the host that
     * serves it, as an {@code execution.cancel} frame (&sect;8.8).
     *
     * <p>The turn is identified by the ACTIVE conversation session plus its
     * in-flight execution ({@code STARTING|RUNNING|CANCELLING}). The host breaks
     * out of its streaming / tool loop and answers with
     * {@code execution.cancelled}, which the host-control handler records
     * through {@link ExecutionRegistry}.</p>
     *
     * @return {@code true} if a cancel frame was delivered to the host
     */
    public boolean cancel(UUID conversationId) {
        Session session = activeSessionOf(conversationId).orElse(null);
        if (session == null) {
            log.debug("No ACTIVE session for conv {} \u2014 nothing to cancel", conversationId);
            return false;
        }
        SessionExecution inFlight = executionRepository
                .findWithLockBySessionIdAndStateIn(session.getId(), IN_FLIGHT_STATES)
                .stream().findFirst().orElse(null);
        if (inFlight == null) {
            log.debug("No in-flight execution on session {} \u2014 nothing to cancel", session.getId());
            return false;
        }
        boolean delivered = executionCommandSender.cancel(
                inFlight.getId(), session, null, CANCEL_REASON_USER, CANCEL_GRACE_SECONDS);
        if (delivered) {
            log.info("Relayed execution.cancel for conv {} (execution {})",
                    conversationId, inFlight.getId());
        } else {
            log.debug("Host socket gone for conv {} \u2014 execution.cancel not delivered", conversationId);
        }
        return delivered;
    }

    /**
     * &sect;3.7: the profile comes from the conversation's pinned version (the
     * assistant pin), never from the host. Conversations without a pin cannot
     * dispatch (graceful no-agent decline).
     */
    private Optional<AgentProfile> pinnedProfileOf(Conversation conversation) {
        UUID pinnedVersionId = conversation.getAgentProfileVersionId();
        if (pinnedVersionId == null) {
            return Optional.empty();
        }
        return agentProfileVersionRepository.findByIdWithTools(pinnedVersionId)
                .map(v -> agentProfileRepository.findById(v.getProfileId()).orElse(null));
    }

    /**
     * Assemble + dispatch one turn onto the unified path. Returns {@code true}
     * when the turn was shipped to a host &mdash; either synchronously on an
     * existing ACTIVE session, or staged behind a fresh offer whose
     * accept/opened continuation will ship it.
     */
    public boolean dispatch(UUID conversationId) {
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            log.warn("Cannot dispatch turn \u2014 conversation {} not found", conversationId);
            return false;
        }
        Conversation conversation = convOpt.get();

        checkQuota(conversation);

        Optional<AgentHost> hostOpt = hostSelectionService.selectForProject(conversation.getProjectId());
        if (hostOpt.isEmpty()) {
            log.warn("No host with live capacity serves project {} \u2014 declining turn for conv {}",
                    conversation.getProjectId(), conversationId);
            // #86 â€” don't silently drop the turn. Surface a one-time SYSTEM
            // notice so the user knows their message was received and will be
            // answered once a host comes online; the #87 backlog drainer
            // re-dispatches this conversation on the next host connect.
            conversationNoticeService.emitNoAgentNotice(conversationId);
            return false;
        }
        UUID hostId = hostOpt.get().getId();

        // Â§3.7/Â§16.1: the profile comes from the conversation's pinned version.
        if (pinnedProfileOf(conversation).isEmpty()) {
            log.warn("Cannot dispatch turn \u2014 conversation {} has no pinned profile version",
                    conversationId);
            conversationNoticeService.emitNoAgentNotice(conversationId);
            return false;
        }

        long preRowSequence = nextAssistantSequence(conversationId);
        boolean staged = stageTurn(conversation, hostId, preRowSequence,
                seq -> buildTurn(conversation, seq), "CONVERSATION_TURN_DISPATCHED");
        if (staged) {
            log.info("Dispatched conversation turn for conv {} via the unified host-control path",
                    conversationId);
        }
        return staged;
    }

    /**
     * Dispatch an engine-orchestrated summarisation turn (#8) onto the unified
     * path. The summary rides the SAME conversation session as a turn on it, so
     * the producing worker keeps its context; the only differences are the
     * summariser system prompt and that the history is the block of older turns
     * being folded (plus any earlier summary) rather than the live window.
     *
     * <p>Caller ({@code ConversationSummaryService}) records the in-flight
     * marker <em>before</em> calling so the eventual completion is routed into a
     * {@code CONTEXT_SUMMARY} row; this method returns {@code false} when no
     * host could take the turn so the caller can release that marker and retry
     * later.</p>
     *
     * @param previousSummaryContent the prior summary's body to carry forward, or null
     * @param olderBlock             the older turns to fold, oldest first (non-empty)
     * @return {@code true} if a summary turn was shipped or staged
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

        Optional<AgentHost> hostOpt = hostSelectionService.selectForProject(conversation.getProjectId());
        if (hostOpt.isEmpty()) {
            log.info("No host with live capacity serves project {} \u2014 deferring summary of conv {}",
                    conversation.getProjectId(), conversationId);
            return false;
        }
        UUID hostId = hostOpt.get().getId();

        if (pinnedProfileOf(conversation).isEmpty()) {
            log.warn("Cannot dispatch summary \u2014 conversation {} has no pinned profile version",
                    conversationId);
            return false;
        }

        long preRowSequence = nextAssistantSequence(conversationId);
        boolean staged = stageTurn(conversation, hostId, preRowSequence,
                seq -> buildSummaryTurn(conversation, seq, previousSummaryContent, olderBlock),
                "CONVERSATION_SUMMARY_DISPATCHED");
        if (staged) {
            log.info("Dispatched summarisation turn for conv {} (folding {} msg(s))",
                    conversationId, olderBlock == null ? 0 : olderBlock.size());
        }
        return staged;
    }

    // ====================================================================
    // Unified staging: reuse an ACTIVE session, otherwise offer a new one
    // ====================================================================

    /**
     * Stage one turn on the unified path.
     *
     * <p><b>Reuse</b> (an ACTIVE session pinned to {@code hostId} exists): the
     * same session row serves the turn. {@link ExecutionRegistry#start} creates
     * the execution row (&sect;11.3.4 one-in-flight guard, per-session sequence
     * assignment) and {@link ExecutionCommandSender#startConversation} ships
     * {@code execution.start} immediately.</p>
     *
     * <p><b>Offer</b> (no usable ACTIVE session): the session row is minted
     * OFFERED by the allocator and the offer goes on the wire. Because
     * {@code session.accept}/{@code session.opened} arrive asynchronously, the
     * assembled turn is parked in {@link PendingConversationTurns} and resumed
     * by the host-control handler once the session is ACTIVE.</p>
     *
     * @param preRowSequence the sequence the caller pre-minted before any
     *                       execution row existed (used on the offer path and as
     *                       the first-turn fallback on the reuse path)
     * @param bundleFactory  builds the wire turn for a given sequence number
     * @param snapshotEvent  the execution_snapshots event type for this turn
     */
    private boolean stageTurn(Conversation conversation, UUID hostId, long preRowSequence,
                             LongFunction<ConversationTurnBundle> bundleFactory,
                             String snapshotEvent) {
        UUID conversationId = conversation.getId();

        Session existing = activeSessionOf(conversationId).orElse(null);
        if (existing != null && !hostId.equals(hostOfSessionInstance(existing))) {
            // The live session is pinned to a different host instance: it cannot
            // serve a turn on the host this dispatch selected. Close it so the
            // offer below can mint a fresh session on the live host.
            log.info("Closing ACTIVE session {} for conv {} (pinned to instance {}, host {} selected)",
                    existing.getId(), conversationId, existing.getHostInstanceId(), hostId);
            sessionAllocator.close(existing.getId(), "HOST_RESELECTED");
            existing = null;
        }

        if (existing != null) {
            return shipOnExistingSession(conversation, existing, preRowSequence,
                    bundleFactory, snapshotEvent);
        }

        UUID sessionId = sessionAllocator.offer(
                SERVICE_TYPE_CONVERSATION, conversationId, SERVICE_TYPE_CONVERSATION,
                conversation.getProjectId(), hostId).orElse(null);
        if (sessionId == null) {
            log.warn("No allocation capacity on host {} for conv {}", hostId, conversationId);
            conversationNoticeService.emitNoAgentNotice(conversationId);
            return false;
        }

        ConversationTurnBundle bundle = bundleFactory.apply(preRowSequence);
        // Park the assembled turn until the host answers session.accept/session.opened.
        pendingConversationTurns.stage(sessionId, conversationId, bundle.input(),
                bundle.toStartPayload(null));
        hostControlWebSocketHandler.sendSessionOffer(
                sessionId, SERVICE_TYPE_CONVERSATION, conversationId);
        log.info("Offered session {} to host {} for conv {} (turn parked until session.opened)",
                sessionId, hostId, conversationId);
        writeSnapshot(conversation, bundle.toStartPayload(null), snapshotEvent);
        return true;
    }

    /** Ship a turn on an already-ACTIVE session (the sticky reuse hot path). */
    private boolean shipOnExistingSession(Conversation conversation, Session session,
                                          long preRowSequence,
                                          LongFunction<ConversationTurnBundle> bundleFactory,
                                          String snapshotEvent) {
        UUID conversationId = conversation.getId();
        long sequenceNo = resolveSequenceNo(session.getId(), preRowSequence);
        ConversationTurnBundle bundle = bundleFactory.apply(sequenceNo);
        SessionExecution execution = executionRegistry.start(
                session.getId(), conversationId.toString(),
                Instant.now().plusSeconds(DEFAULT_TIMEOUT_SECONDS), bundle.input()).orElse(null);
        if (execution == null) {
            log.warn("execution.start refused for session {} (conv {}) \u2014 a turn is already in flight",
                    session.getId(), conversationId);
            return false;
        }
        boolean sent = executionCommandSender.startConversation(
                execution.getId(), session, bundle.toStartPayload(execution.getId()));
        if (!sent) {
            log.warn("Host socket gone for session {} (conv {}) \u2014 execution.start not delivered",
                    session.getId(), conversationId);
            return false;
        }
        writeSnapshot(conversation, bundle.toStartPayload(execution.getId()), snapshotEvent);
        return true;
    }

    /**
     * Assemble the &sect;8.1 input block for a normal conversation turn: the same
     * transcript the legacy {@code inference.assign} shipped, rewrapped into the
     * execution-lifecycle shape.
     *
     * <p>The input assembler takes the conversation id as its session id (it
     * uses it for the context-manifest row and the spec's correlation only); the
     * authoritative session identity on the wire comes from the allocator-owned
     * {@link Session} row, which the command sender stamps onto the envelope.</p>
     */
    private ConversationTurnBundle buildTurn(Conversation conversation, long sequenceNo) {
        UUID conversationId = conversation.getId();
        Map<String, Object> input = executionInputAssembler.assembleConversationInput(
                conversationId, conversationId, conversation.getProjectId(), sequenceNo);
        return new ConversationTurnBundle(conversationId, input, sequenceNo);
    }

    /**
     * Assemble the &sect;8.1 input block for an engine-orchestrated
     * summarisation turn (#8): the summariser system prompt, the block of older
     * turns being folded, and the carry-forward summary, shipped as one more
     * execution on the conversation's session.
     */
    private ConversationTurnBundle buildSummaryTurn(Conversation conversation, long sequenceNo,
                                                    String previousSummaryContent,
                                                    List<ConversationMessage> olderBlock) {
        List<InferenceMessage> messages = new ArrayList<>();
        messages.add(new InferenceMessage("system", SUMMARISER_SYSTEM_PROMPT, null, null));
        if (previousSummaryContent != null && !previousSummaryContent.isBlank()) {
            messages.add(new InferenceMessage("system",
                    SUMMARY_WIRE_PREFIX + previousSummaryContent, null, null));
        }
        if (olderBlock != null) {
            for (ConversationMessage m : olderBlock) {
                messages.add(new InferenceMessage(
                        m.getRole() == null ? "user" : m.getRole().name().toLowerCase(),
                        m.getContent(), null, null));
            }
        }
        messages.add(new InferenceMessage("user", SUMMARISER_USER_INSTRUCTION, null, null));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", messages);
        input.put("toolPolicy", Map.of("activeToolNames", List.of(), "approvalMode", "ENGINE"));
        input.put("output", Map.of(
                "stream", true,
                "responseSequenceNo", sequenceNo,
                "format", "TEXT"));
        return new ConversationTurnBundle(conversation.getId(), input, sequenceNo);
    }

    /**
     * The next sequence for a turn on an already-ACTIVE session: one past the
     * highest sequence the session's execution history already carries, falling
     * back to the caller's pre-minted value when the session has no executions
     * yet (the first turn on a session offered by an earlier dispatch).
     */
    private long resolveSequenceNo(UUID sessionId, long fallback) {
        return executionRepository.findBySessionIdOrderBySequenceNoDesc(sessionId).stream()
                .findFirst()
                .map(e -> e.getSequenceNo() == null ? fallback : (long) e.getSequenceNo() + 1L)
                .orElse(fallback);
    }

    /** The conversation's ACTIVE conversation session, if any. */
    private Optional<Session> activeSessionOf(UUID conversationId) {
        return sessionRepository.findByRefIdAndServiceTypeAndAllocationStateIn(
                conversationId, SERVICE_TYPE_CONVERSATION, List.of(SessionAllocator.ALLOC_STATE_ACTIVE));
    }

    /** The durable host a session's instance belongs to (null when unresolvable). */
    private UUID hostOfSessionInstance(Session session) {
        if (session.getHostInstanceId() == null) {
            return null;
        }
        return hostInstanceRepository.findById(session.getHostInstanceId())
                .map(AgentHostInstance::getAgentHostId)
                .orElse(null);
    }

    /** The next assistant sequence for the conversation (max persisted + 1). */
    private long nextAssistantSequence(UUID conversationId) {
        List<ConversationMessage> all = conversationService.listMessages(conversationId);
        return all.isEmpty() ? 0L : all.get(all.size() - 1).getSequenceNo() + 1;
    }

    /**
     * Phase 8c pre-flight quota check. We charge 0 tokens (the LLM hasn't run
     * yet) and rely on prior consumption to drive the block. A hard block raises
     * {@code QuotaExceededException} which the REST layer translates to 429; for
     * WS we refuse to dispatch and let the client surface the error.
     *
     * <p>When the conversation has a pinned assistant, check at SERVICE scope
     * (scopeId = assistantId). {@code check(SERVICE, assistantId, ...)}
     * internally walks SERVICE&nbsp;&rarr;&nbsp;PROJECT&nbsp;&rarr;&nbsp;GROUP&nbsp;&rarr;&nbsp;ORG
     * via {@code buildScopeChain} and returns the most restrictive decision, so
     * a SERVICE RESERVATION block takes priority over the GROUP ceiling &mdash;
     * the 429 will carry {@code scopeHit=SERVICE}. For conversations with no
     * assistant, fall back to PROJECT scope (scopeId = projectId).</p>
     */
    private void checkQuota(Conversation conversation) {
        UUID dispatchProjectId = conversation.getProjectId();
        UUID assistantId = conversation.getAssistantId();
        if (dispatchProjectId == null && assistantId == null) {
            return;
        }
        QuotaDecision decision;
        UUID effectiveScopeId;
        if (assistantId != null) {
            decision = quotaPolicyEngine.check(
                    QuotaScope.SERVICE, assistantId, QuotaResourceType.TOKENS, 0L);
            effectiveScopeId = assistantId;
        } else {
            decision = quotaPolicyEngine.check(
                    QuotaScope.PROJECT, dispatchProjectId, QuotaResourceType.TOKENS, 0L);
            effectiveScopeId = dispatchProjectId;
        }
        if (decision.isBlocked()) {
            log.warn("Conversation turn blocked by quota -- conv {} scopeId {} (used {} of {})",
                    conversation.getId(), effectiveScopeId,
                    decision.getConsumedAmount(), decision.getLimitAmount());
            throw new ai.myrmec.engine._system.exception.QuotaExceededException(
                    decision.getScopeHit() != null ? decision.getScopeHit() : QuotaScope.PROJECT,
                    effectiveScopeId,
                    QuotaResourceType.TOKENS,
                    decision.getLimitAmount(),
                    decision.getConsumedAmount());
        }
    }

    /**
     * Phase 9a &mdash; archive the dispatched payload so V2 replay has the same
     * inputs the agent saw. Best-effort: never abort the caller on a snapshot
     * failure.
     */
    private void writeSnapshot(Conversation conversation, ExecutionStartPayload payload,
                               String eventType) {
        snapshotWriter.write(ai.myrmec.engine.snapshot.SnapshotWriter.SnapshotRequest.builder()
                .projectId(conversation.getProjectId())
                .eventType(eventType)
                .agentId(conversation.getAgentId())
                .conversationId(conversation.getId())
                .source(conversation.getSource() == null ? null : conversation.getSource().name())
                .serviceAccountId(conversation.getServiceAccountId())
                .externalUserRef(conversation.getExternalUserRef())
                .userId(conversation.getCreatedBy())
                .payload(payload)
                .build());
    }

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

    /** Label prefixed to a context summary's body on the wire so the model
     * reads it as prior context rather than a fresh instruction. */
    private static final String SUMMARY_WIRE_PREFIX = "[Summary of earlier conversation]\n";

    /**
     * One assembled conversation turn: the &sect;8.1 input block plus the
     * routing/sequence metadata the wire payload needs.
     *
     * <p>The {@code executionId} is minted by {@link ExecutionRegistry#start},
     * which is only legal once the session is allocation-ACTIVE (&sect;7.4: no
     * {@code execution.start} before {@code session.opened}). The wire payload is
     * therefore built lazily &mdash; once with a null id for the dispatch
     * snapshot, and once with the real id for the send.</p>
     *
     * <p>The payload's own {@code sessionId} is the conversation id, mirroring
     * the {@code requestId} convention (&sect;8.1): the transport-level session
     * identity rides the envelope, stamped by the command sender from the
     * allocator-owned session row.</p>
     */
    private record ConversationTurnBundle(UUID conversationId, Map<String, Object> input, long sequenceNo) {

        ExecutionStartPayload toStartPayload(UUID executionId) {
            @SuppressWarnings("unchecked")
            List<InferenceMessage> messages = input.get("messages") instanceof List<?> list
                    ? (List<InferenceMessage>) list : List.of();
            List<String> activeTools = List.of();
            if (input.get("toolPolicy") instanceof Map<?, ?> policy
                    && policy.get("activeToolNames") instanceof List<?> tools) {
                activeTools = tools.stream().map(String::valueOf).toList();
            }
            return new ExecutionStartPayload(
                    executionId,
                    conversationId,
                    (int) sequenceNo,
                    conversationId.toString(),
                    Instant.now().plusSeconds(DEFAULT_TIMEOUT_SECONDS),
                    new ExecutionStartPayload.Input(messages, null, null),
                    new ExecutionStartPayload.ToolPolicy(activeTools, "ENGINE"),
                    new ExecutionStartPayload.Output(true, (int) sequenceNo, "TEXT"));
        }
    }
}
