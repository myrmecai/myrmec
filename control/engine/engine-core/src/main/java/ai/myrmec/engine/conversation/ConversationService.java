package ai.myrmec.engine.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.myrmec.engine._system.common.Constants;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.CapacityException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.HostSelectionService;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantAgentBindingRepository;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.assistant.AssistantVersion;
import ai.myrmec.engine.assistant.AssistantVersionRepository;
import ai.myrmec.engine.agent.AgentProfileVersionRepository;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.conversation.event.ConversationArchivedEvent;
import ai.myrmec.engine.conversation.event.ConversationClosedEvent;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Coordinates conversation creation, ACL, and message appending.
 *
 * <p>
 * Sequence numbers are assigned <em>here</em> rather than by the
 * database because the engine streams the same sequence_no to all WS
 * viewers and would otherwise need a round-trip to learn what the
 * database picked. Acquiring a row-level lock on the parent conversation
 * row in {@link #appendMessage} serialises concurrent writers per
 * conversation while leaving cross-conversation throughput unaffected.
 * </p>
 *
 * <p>
 * Phase 6a only persists; the streaming broker (6c) and context-window
 * manager (6d) layer on top.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final ConversationParticipantRepository participantRepository;
    private final AuditEventService auditEventService;
    private final AssistantRepository assistantRepository;
    private final AssistantVersionRepository assistantVersionRepository;
    private final AssistantAgentBindingRepository assistantAgentBindingRepository;
    private final AgentProfileVersionRepository agentProfileVersionRepository;
    private final ai.myrmec.engine.inference.SessionContextAssembler sessionContextAssembler;
    private final ai.myrmec.engine.governance.GovernancePolicyResolver governancePolicyResolver;
    private final ai.myrmec.engine.context.InstructionAssetVersionResolver instructionAssetVersionResolver;
    private final HostSelectionService hostSelectionService;
    private final TeamService teamService;
    private final SessionAllocator sessionAllocator;
    private final ai.myrmec.engine.inference.SessionRepository sessionRepository;
    private final ModelService modelService;
    /** Lazy provider: HostControlWebSocketHandler injects ConversationService,
     * so a direct field would cycle. Resolved only at the offer call site. */
    private final org.springframework.beans.factory.ObjectProvider<
            ai.myrmec.engine._controllers.websocket.HostChannelWebSocketHandler> hostControlWebSocketHandler;
    /**
     * 2026-09-21 close/archive design 5.2: host session.close pushes ride the
     * event listener (injecting HostControlWebSocketHandler here would cycle
     * with ConversationService).
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Create a durable (un-started) conversation from an assistant.
     *
     * <p>
     * The assistant owns the Team (1:1), so the team is derived here rather
     * than supplied. The assistant's currently-published version is pinned onto
     * the conversation for the life of the session. No host/agent is selected
     * (that happens at {@link #start(UUID)}); {@code agentId} and the profile
     * version pin are runtime concerns.
     * </p>
     */
    @Transactional
    public Conversation createConversation(
            UUID assistantId,
            UUID createdBy,
            String title) {
        Assistant assistant = assistantRepository.findByIdForUpdate(assistantId)
                .orElseThrow(() -> ResourceNotFoundException.of("Assistant", assistantId.toString()));

        if (assistant.getArchivedAt() != null) {
            throw new BadRequestException("Assistant is archived and cannot start new conversations.");
        }
        if (assistant.isDisabled()) {
            throw new BadRequestException("Assistant is disabled and cannot start new conversations.");
        }
        UUID currentVersionId = assistant.getCurrentVersionId();
        if (currentVersionId == null) {
            throw new BadRequestException("Assistant has no published version to start a conversation with.");
        }
        AssistantVersion version = assistantVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> ResourceNotFoundException.of("AssistantVersion", currentVersionId.toString()));

        Conversation conversation = new Conversation();
        conversation.setTeamId(assistant.getTeamId());
        conversation.setAssistantId(assistant.getId());
        conversation.setAssistantVersionId(version.getId());
        conversation.setCreatedBy(createdBy);
        conversation.setTitle(title);
        conversation.setStatus(Conversation.Status.ACTIVE);

        // Build and set the context snapshot if the governance profile has
        // CONTEXT_PINNING=ON
        ai.myrmec.engine.context.ContextSnapshot snapshot = buildContextSnapshot(assistant.getTeamId());
        conversation.setContextSnapshot(snapshot);

        conversation = conversationRepository.save(conversation);

        if (createdBy != null) {
            ConversationParticipant owner = new ConversationParticipant();
            owner.setConversationId(conversation.getId());
            owner.setUserId(createdBy);
            owner.setRole(ConversationParticipant.Role.OWNER);
            participantRepository.save(owner);
        }
        return conversation;
    }

    /**
     * Start (or resume) a conversation: attach an active run to an already
     * created transcript. This is where the runtime is prepared — the profile
     * version is pinned (first start only, pin-once) and a host is selected by
     * capacity.
     *
     * <p>
     * Create produces only a durable transcript; a user may send messages
     * only once the conversation has been started (and the agent is ready).
     * </p>
     */
    @Transactional
    public Conversation start(UUID conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ResourceNotFoundException.of("Conversation", conversationId));

        if (conversation.getStatus() != Conversation.Status.ACTIVE) {
            throw new BadRequestException("Conversation is " + conversation.getStatus()
                    + " and cannot be started");
        }

        // Resume: an existing session is attached and still open.
        if (conversation.getCurrentSessionId() != null
                && isOpenSession(conversation.getCurrentSessionId())) {
            log.info("Resuming conversation {} with existing session {}", conversationId,
                    conversation.getCurrentSessionId());
            return conversation;
        }

        startNewSession(conversation);
        return conversation;
    }

    /** Whether a session id points at a still-open (not CLOSED) session. */
    private boolean isOpenSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .map(s -> !ai.myrmec.engine.inference.SessionAllocator.ALLOC_STATE_CLOSED
                        .equals(s.getAllocationState()))
                .orElse(false);
    }

    /**
     * Allocate + offer a fresh session for the conversation. The V2
     * {@code session.open} is assembled later (on the host's
     * {@code session.accept}) from the conversation's frozen
     * {@code assistant_version_id} bindings — no profile resolution happens
     * here.
     */
    private void startNewSession(Conversation conversation) {
        UUID conversationId = conversation.getId();

        AgentHost host = hostSelectionService.selectForTeam(conversation.getTeamId())
                .orElseThrow(() -> new CapacityException(
                        "No host with live capacity serves the team"));
        UUID hostId = host.getId();

        UUID sessionId = sessionAllocator.offer(
                Constants.SERVICE_TYPE_CONVERSATION, conversationId,
                Constants.SERVICE_TYPE_CONVERSATION, conversation.getTeamId(), hostId)
                .orElseThrow(() -> new CapacityException(
                        "No allocation capacity on host " + hostId + " for conv " + conversationId));

        conversation.setCurrentSessionId(sessionId);
        conversationRepository.save(conversation);

        hostControlWebSocketHandler.getObject().sendSessionOffer(
                sessionId, Constants.SERVICE_TYPE_CONVERSATION, conversationId);

        log.info("Started conversation {} — offered session {} on host {}",
                conversationId, sessionId, hostId);
    }

    /** Next assistant sequence for a conversation (max persisted + 1). */
    public long nextAssistantSequence(UUID conversationId) {
        return messageRepository.countByConversationId(conversationId) + 1;
    }
    @Transactional
    public ConversationParticipant addParticipant(
            UUID conversationId,
            UUID userId,
            ConversationParticipant.Role role) {
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        Optional<ConversationParticipant> existing = participantRepository.findByConversationIdAndUserId(conversationId,
                userId);
        if (existing.isPresent()) {
            ConversationParticipant p = existing.get();
            p.setRole(role);
            return participantRepository.save(p);
        }
        ConversationParticipant p = new ConversationParticipant();
        p.setConversationId(conversationId);
        p.setUserId(userId);
        p.setRole(role);
        return participantRepository.save(p);
    }

    /**
     * Append a message to the end of a conversation. The sequence number
     * is assigned monotonically by looking at the current highest
     * persisted row. Callers should drive concurrent writers through this
     * method so the increment is consistent.
     */
    @Transactional
    public ConversationMessage appendMessage(
            UUID conversationId,
            ConversationMessage.Role role,
            String content,
            UUID authorUserId,
            UUID authorAgentId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        if (role == ConversationMessage.Role.USER
                && conversation.getStatus() != Conversation.Status.ACTIVE) {
            // Read-only guard (2026-09-21 close/archive lifecycle design
            // section 4.5): a CLOSED or ARCHIVED conversation is a frozen
            // transcript. Engine-internal rows (ASSISTANT / SYSTEM /
            // CONTEXT_SUMMARY) are unaffected so in-flight completions can
            // still land after a close.
            throw new BadRequestException("Conversation is " + conversation.getStatus()
                    + " and cannot receive new messages");
        }

        long nextSequence = messageRepository
                .findFirstByConversationIdOrderBySequenceNoDesc(conversationId)
                .map(m -> m.getSequenceNo() + 1)
                .orElse(0L);

        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setSequenceNo(nextSequence);
        message.setRole(role);
        message.setContent(content);
        message.setAuthorUserId(authorUserId);
        message.setAuthorAgentId(authorAgentId);
        message = messageRepository.save(message);

        // Auto-title (#104e): give an untitled conversation a human label
        // derived from its opening user turn so it's findable in history
        // lists. Heuristic only â€” a summarizer-model title (the
        // `summarizer_model_code` setting) is a future upgrade that needs
        // model-call infrastructure the control plane doesn't own. A user
        // rename always wins: we only fill a blank title, once.
        if (role == ConversationMessage.Role.USER
                && nextSequence == 0L
                && (conversation.getTitle() == null || conversation.getTitle().isBlank())) {
            String derived = deriveTitle(content);
            if (derived != null) {
                conversation.setTitle(derived);
            }
        }

        // Bump conversation.updated_at so list views can sort by recency.
        conversation.setUpdatedAt(java.time.Instant.now());
        conversationRepository.save(conversation);
        return message;
    }

    /**
     * Append an engine-generated {@link ConversationMessage.Role#CONTEXT_SUMMARY}
     * row (#8) carrying the summary text and its coverage marker in
     * {@code payload_json}. Unlike {@link #appendMessage} this never touches the
     * auto-title heuristic and always records the marker so context assembly can
     * fold the covered turns deterministically.
     *
     * @param payloadJson serialised {@link ContextSummaryMarker}
     */
    @Transactional
    public ConversationMessage appendContextSummary(
            UUID conversationId,
            String content,
            String payloadJson,
            UUID authorAgentId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        long nextSequence = messageRepository
                .findFirstByConversationIdOrderBySequenceNoDesc(conversationId)
                .map(m -> m.getSequenceNo() + 1)
                .orElse(0L);

        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setSequenceNo(nextSequence);
        message.setRole(ConversationMessage.Role.CONTEXT_SUMMARY);
        message.setContent(content);
        message.setAuthorAgentId(authorAgentId);
        message.setPayloadJson(payloadJson);
        message = messageRepository.save(message);

        conversation.setUpdatedAt(java.time.Instant.now());
        conversationRepository.save(conversation);
        return message;
    }

    /**
     * Append an engine-generated {@link ConversationMessage.Role#SYSTEM} notice
     * row (#86) â€” e.g. the "no agent online" message shown when a turn cannot be
     * dispatched because no warm worker is available. Like
     * {@link #appendContextSummary} it bypasses the auto-title heuristic and
     * records {@code payload_json} so context assembly can recognise (and skip)
     * the marker, keeping the contradictory notice out of the agent's history
     * window once a worker does come online.
     *
     * @param payloadJson serialised marker (e.g.
     *                    {@code {"kind":"NO_AGENT_NOTICE"}})
     */
    @Transactional
    public ConversationMessage appendSystemNotice(
            UUID conversationId,
            String content,
            String payloadJson) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        long nextSequence = messageRepository
                .findFirstByConversationIdOrderBySequenceNoDesc(conversationId)
                .map(m -> m.getSequenceNo() + 1)
                .orElse(0L);

        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setSequenceNo(nextSequence);
        message.setRole(ConversationMessage.Role.SYSTEM);
        message.setContent(content);
        message.setPayloadJson(payloadJson);
        message = messageRepository.save(message);

        conversation.setUpdatedAt(java.time.Instant.now());
        conversationRepository.save(conversation);
        return message;
    }

    /**
     * Append a tool-call result row (#22) — the durable transcript surface for
     * a {@code TOOL_COMPLETED} execution event. Mirrors the mock's
     * {@code appendToolRow}: {@code toolCallId} + {@code payloadJson}
     * ({@code {toolName, durationMs, error, command?}}) with a bounded content
     * preview. Like {@link #appendSystemNotice} it bypasses the auto-title
     * heuristic and never bumps the read-only guard (tool rows are
     * engine-internal).
     */
    @Transactional
    public ConversationMessage appendToolRow(
            UUID conversationId,
            String toolCallId,
            String payloadJson,
            String content) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        long nextSequence = messageRepository
                .findFirstByConversationIdOrderBySequenceNoDesc(conversationId)
                .map(m -> m.getSequenceNo() + 1)
                .orElse(0L);

        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setSequenceNo(nextSequence);
        message.setRole(ConversationMessage.Role.TOOL);
        message.setContent(content);
        message.setToolCallId(toolCallId);
        message.setPayloadJson(payloadJson);
        message = messageRepository.save(message);

        conversation.setUpdatedAt(java.time.Instant.now());
        conversationRepository.save(conversation);
        return message;
    }

    private static final int AUTO_TITLE_MAX = 60;

    /**
     * Derive a short, single-line title from a message body: collapse
     * whitespace, trim, and cut to {@link #AUTO_TITLE_MAX} chars on a word
     * boundary (with an ellipsis) when longer. Returns {@code null} for
     * empty content so the conversation simply stays untitled.
     */
    private static String deriveTitle(String content) {
        if (content == null) {
            return null;
        }
        String flat = content.strip().replaceAll("\\s+", " ");
        if (flat.isEmpty()) {
            return null;
        }
        if (flat.length() <= AUTO_TITLE_MAX) {
            return flat;
        }
        String cut = flat.substring(0, AUTO_TITLE_MAX);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > AUTO_TITLE_MAX / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.strip() + "\u2026";
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> listMessages(UUID conversationId) {
        return messageRepository.findByConversationIdOrderBySequenceNoAsc(conversationId);
    }

    /**
     * Paginated scrollback. Returns up to {@code limit} messages ending at
     * the newest row (or, when {@code before} is set, the newest row strictly
     * older than that {@code sequence_no}), in ascending sequence order so the
     * UI can prepend an older page without resorting. {@code limit} must be
     * positive; callers wanting the whole transcript use the no-arg overload.
     */
    @Transactional(readOnly = true)
    public List<ConversationMessage> listMessages(UUID conversationId, int limit, Long before) {
        if (limit <= 0) {
            return List.of();
        }
        Pageable page = PageRequest.of(0, limit);
        List<ConversationMessage> desc = (before == null)
                ? messageRepository.findByConversationIdOrderBySequenceNoDesc(conversationId, page)
                : messageRepository.findByConversationIdAndSequenceNoLessThanOrderBySequenceNoDesc(
                        conversationId, before, page);
        List<ConversationMessage> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        return asc;
    }

    /**
     * Set or clear the participant pin flag on a single message. The
     * message must belong to the given conversation (guards against a
     * cross-conversation id mismatch). Returns the updated row.
     */
    @Transactional
    public ConversationMessage setMessagePinned(
            UUID conversationId, UUID messageId, boolean pinned) {
        ConversationMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", messageId));
        if (!conversationId.equals(message.getConversationId())) {
            throw new IllegalStateException(
                    "Message " + messageId + " does not belong to conversation " + conversationId);
        }
        message.setPinned(pinned);
        return messageRepository.save(message);
    }

    /**
     * Record, change, or clear participant feedback on a single ASSISTANT
     * message (#104a). The message must belong to the given conversation
     * and must be an {@link ConversationMessage.Role#ASSISTANT} turn
     * (only model answers are rateable).
     *
     * <p>
     * A {@code null} or blank {@code rating} clears any existing
     * feedback (un-rates the message); a non-null rating is parsed
     * case-insensitively to {@link ConversationMessage.Rating} and an
     * unknown value is rejected. Every mutation is attributed to
     * {@code actorUserId} and audited.
     * </p>
     */
    @Transactional
    public ConversationMessage setMessageFeedback(
            UUID conversationId, UUID messageId, String rating, String reason, UUID actorUserId) {
        ConversationMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", messageId));
        if (!conversationId.equals(message.getConversationId())) {
            throw new IllegalStateException(
                    "Message " + messageId + " does not belong to conversation " + conversationId);
        }
        if (message.getRole() != ConversationMessage.Role.ASSISTANT) {
            throw new IllegalStateException(
                    "Only ASSISTANT messages can be rated; message " + messageId
                            + " is " + message.getRole());
        }

        ConversationMessage.Rating parsed = parseRating(rating);
        if (parsed == null) {
            message.setFeedbackRating(null);
            message.setFeedbackReason(null);
            message.setFeedbackBy(null);
            message.setFeedbackAt(null);
        } else {
            message.setFeedbackRating(parsed);
            message.setFeedbackReason((reason == null || reason.isBlank()) ? null : reason.trim());
            message.setFeedbackBy(actorUserId);
            message.setFeedbackAt(Instant.now());
        }
        ConversationMessage saved = messageRepository.save(message);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("messageId", messageId.toString());
        payload.put("rating", parsed == null ? null : parsed.name());
        payload.put("hasReason", parsed != null && saved.getFeedbackReason() != null);
        // audit event recorded above
        return saved;
    }

    private static ConversationMessage.Rating parseRating(String rating) {
        if (rating == null || rating.isBlank()) {
            return null;
        }
        try {
            return ConversationMessage.Rating.valueOf(rating.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid feedback rating '" + rating + "'; expected UP or DOWN");
        }
    }

    /**
     * Edit a prior USER turn and resend it (#104b). The active branch is
     * truncated at the edited message â€” every non-superseded row from that
     * message's {@code sequence_no} onward is soft-superseded (retained, not
     * destroyed) â€” and a fresh USER message carrying {@code newContent} is
     * appended at the head of the new branch, linked back to the message it
     * replaces via {@code parentMessageId}. The caller then dispatches a turn
     * so the agent answers the edited message.
     *
     * @return the newly appended USER message
     */
    @Transactional
    public ConversationMessage editUserMessageAndResend(
            UUID conversationId, UUID messageId, String newContent, UUID actorUserId) {
        ConversationMessage edited = requireActiveMessage(conversationId, messageId);
        if (edited.getRole() != ConversationMessage.Role.USER) {
            throw new IllegalStateException(
                    "Only USER messages can be edited and resent; message " + messageId
                            + " is " + edited.getRole());
        }
        if (newContent == null || newContent.isBlank()) {
            throw new IllegalArgumentException("Edited message content must not be blank");
        }

        supersedeFrom(conversationId, edited.getSequenceNo());

        ConversationMessage replacement = appendMessage(
                conversationId, ConversationMessage.Role.USER, newContent, actorUserId, null);
        replacement.setParentMessageId(messageId);
        replacement = messageRepository.save(replacement);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("editedMessageId", messageId.toString());
        payload.put("replacementMessageId", replacement.getId().toString());
        // audit event recorded above
        return replacement;
    }

    /**
     * Regenerate an ASSISTANT answer (#104b). The assistant turn (and any
     * rows after it) are soft-superseded; the preceding USER turn remains the
     * head of the active branch so a re-dispatch re-runs the same turn. The
     * caller dispatches a fresh turn after this returns.
     *
     * @return the superseded assistant message
     */
    @Transactional
    public ConversationMessage regenerateAssistantMessage(
            UUID conversationId, UUID messageId, UUID actorUserId) {
        ConversationMessage assistant = requireActiveMessage(conversationId, messageId);
        if (assistant.getRole() != ConversationMessage.Role.ASSISTANT) {
            throw new IllegalStateException(
                    "Only ASSISTANT messages can be regenerated; message " + messageId
                            + " is " + assistant.getRole());
        }

        supersedeFrom(conversationId, assistant.getSequenceNo());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("regeneratedMessageId", messageId.toString());
        // audit event recorded above
        return assistant;
    }

    /**
     * Load a message, asserting it belongs to the conversation and is on the
     * active (non-superseded) branch.
     */
    private ConversationMessage requireActiveMessage(UUID conversationId, UUID messageId) {
        ConversationMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", messageId));
        if (!conversationId.equals(message.getConversationId())) {
            throw new IllegalStateException(
                    "Message " + messageId + " does not belong to conversation " + conversationId);
        }
        if (message.isSuperseded()) {
            throw new IllegalStateException(
                    "Message " + messageId + " is already superseded");
        }
        return message;
    }

    /** Soft-supersede every non-superseded row at or beyond {@code fromSeq}. */
    private void supersedeFrom(UUID conversationId, long fromSeq) {
        List<ConversationMessage> tail = messageRepository
                .findByConversationIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(
                        conversationId, fromSeq);
        for (ConversationMessage m : tail) {
            if (!m.isSuperseded()) {
                m.setSuperseded(true);
            }
        }
        messageRepository.saveAll(tail);
    }

    @Transactional(readOnly = true)
    public Conversation findById(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));
    }

    /**
     * Per-turn context-window read-model (UC-013 header "context bagel").
     *
     * <p>{@code contextTokens} is the token count of the conversation's latest
     * ASSISTANT turn — an approximation of the current context size, since the
     * engine does not yet persist per-turn prompt-token usage separately.
     * {@code maxContextTokens} is the serving model's declared context-window
     * ceiling.</p>
     */
    @Transactional(readOnly = true)
    public ai.myrmec.engine.conversation.dto.ConversationContextResponse context(UUID conversationId) {
        Conversation conversation = findById(conversationId);

        // Primary source: the live context size tracked from MODEL_USAGE
        // events. Fall back to the latest ASSISTANT turn's token count when
        // the conversation predates context-token tracking.
        Integer contextTokens = conversation.getContextTokens();
        if (contextTokens == null) {
            List<ConversationMessage> all = messageRepository
                    .findByConversationIdOrderBySequenceNoAsc(conversationId);
            for (int i = all.size() - 1; i >= 0; i--) {
                ConversationMessage m = all.get(i);
                if (m.getRole() == ConversationMessage.Role.ASSISTANT && m.getTokenCount() != null) {
                    contextTokens = m.getTokenCount();
                    break;
                }
            }
        }

        Integer maxContextTokens = resolveContextWindow(conversation);

        return ai.myrmec.engine.conversation.dto.ConversationContextResponse
                .of(contextTokens, maxContextTokens);
    }

    /**
     * The serving model's context-window ceiling, or null when the assistant's
     * PRIMARY binding has no published profile version / model, or the model
     * declares no window. Never throws — a lookup failure must not block the
     * context read.
     */
    private Integer resolveContextWindow(Conversation conversation) {
        try {
            UUID assistantVersionId = conversation.getAssistantVersionId();
            if (assistantVersionId == null) {
                return null;
            }
            return assistantAgentBindingRepository
                    .findByAssistantVersionIdAndRoleType(
                            assistantVersionId,
                            ai.myrmec.engine.assistant.AssistantAgentBinding.RoleType.PRIMARY)
                    .stream().findFirst()
                    .map(ai.myrmec.engine.assistant.AssistantAgentBinding::getAgentProfileVersionId)
                    .flatMap(agentProfileVersionRepository::findById)
                    .map(ai.myrmec.engine.agent.AgentProfileVersion::getDefaultModel)
                    .map(modelService::findByCode)
                    .map(ai.myrmec.engine.model.Model::getContextWindowTokens)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Render a conversation transcript to Markdown for export / handoff
     * (#104d) and record the export as an audited action. The caller's
     * ACL is enforced upstream by {@code @conversationAccess.canView}; we
     * only attribute and stamp the event here. Tool / approval rows are
     * included so the export is a faithful record of the session.
     */
    @Transactional
    public String exportMarkdown(UUID conversationId, UUID actorUserId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));
        List<ConversationMessage> messages = messageRepository.findByConversationIdOrderBySequenceNoAsc(conversationId);

        String title = (conversation.getTitle() == null || conversation.getTitle().isBlank())
                ? "Conversation"
                : conversation.getTitle().trim();
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("_Exported ").append(Instant.now()).append(" Â· ")
                .append(messages.size()).append(" message")
                .append(messages.size() == 1 ? "" : "s").append("_\n\n");

        int n = 0;
        for (ConversationMessage m : messages) {
            n++;
            String role = m.getRole() == null ? "MESSAGE" : m.getRole().name();
            String heading = role.charAt(0) + role.substring(1).toLowerCase().replace('_', ' ');
            md.append("## ").append(n).append(". ").append(heading);
            if (m.getModelCode() != null && !m.getModelCode().isBlank()) {
                md.append(" (").append(m.getModelCode()).append(")");
            }
            md.append("\n\n");
            String content = m.getContent() == null ? "" : m.getContent();
            md.append(content).append("\n\n");
            if (m.getFeedbackRating() != null) {
                md.append("> Feedback: ")
                        .append(m.getFeedbackRating() == ConversationMessage.Rating.UP ? "ðŸ‘" : "ðŸ‘Ž");
                if (m.getFeedbackReason() != null && !m.getFeedbackReason().isBlank()) {
                    md.append(" â€” ").append(m.getFeedbackReason());
                }
                md.append("\n\n");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("format", "markdown");
        payload.put("messageCount", messages.size());
        // audit event recorded above
        return md.toString();
    }

    /**
     * List conversations under a Team ordered by most-recently-updated
     * first. Used by the chat sidebar to show the user the threads they
     * can resume.
     *
     * <p>
     * 2026-09-21 close/archive lifecycle design section 4.4: CLOSED and
     * ARCHIVED rows are hidden unless the caller opts in via
     * {@code includeClosed} / {@code includeArchived}, so the default list
     * shows only resumable ACTIVE chats.
     * </p>
     */
    @Transactional(readOnly = true)
    public List<Conversation> listByTeam(UUID teamId, boolean includeClosed, boolean includeArchived) {
        return conversationRepository.findByTeamIdOrderByUpdatedAtDesc(teamId).stream()
                .filter(c -> includeClosed || c.getStatus() != Conversation.Status.CLOSED)
                .filter(c -> includeArchived || c.getStatus() != Conversation.Status.ARCHIVED)
                .toList();
    }

    /**
     * Close any active inference sessions for this conversation. Called when
     * the conversation is archived or closed (the session stays open across
     * turns; close it here on archive/close).
     *
     * <p>
     * With the warm-worker {@code agents} model removed, there are no
     * separate workers to release — the session IS the runtime span and is
     * closed directly.
     * </p>
     */
    private void releaseBoundWorker(UUID conversationId) {
        try {
            // Close any active CONVERSATION sessions for this conversation
            // (the session stays open across turns; close it here on archive).
            sessionContextAssembler.closeSessionsByRefId(conversationId, "CONVERSATION");
        } catch (Exception e) {
            log.warn("Failed to close sessions for archived conversation {}: {}",
                    conversationId, e.getMessage());
        }
    }

    /**
     * Rename-only owner update (2026-09-21 close/archive lifecycle design
     * section 4.3): the {@code status} transition surface that used to live
     * here is gone - closing is {@link #closeConversation} and archiving is
     * {@link #archiveConversation}. A {@code null} title leaves the row
     * untouched. Read-only is about messages, not the title: renaming a
     * CLOSED or ARCHIVED conversation is allowed.
     */
    @Transactional
    public Conversation updateConversation(UUID conversationId, String title) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        if (title != null) {
            conversation.setTitle(title);
        }
        return conversationRepository.save(conversation);
    }

    /**
     * Close a conversation (owner-only {@code POST /{id}/close}; 2026-09-21
     * close/archive lifecycle design section 4.1): ACTIVE becomes the
     * terminal CLOSED state and the same teardown as archiving runs
     * ({@link #releaseBoundWorker}: worker release + session close). The
     * close trail is stamped with reason {@code USER_ENDED}.
     *
     * <p>
     * Closing an already-CLOSED conversation is idempotent and returns
     * the row unchanged. Closing an ARCHIVED conversation is rejected with
     * {@link IllegalArgumentException} (mapped to 400).
     * </p>
     */
    @Transactional
    public Conversation closeConversation(UUID conversationId, UUID userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));
        if (conversation.getStatus() == Conversation.Status.CLOSED) {
            return conversation;
        }
        if (conversation.getStatus() == Conversation.Status.ARCHIVED) {
            throw new IllegalArgumentException("Cannot close an archived conversation");
        }
        conversation.setStatus(Conversation.Status.CLOSED);
        conversation.setClosedAt(Instant.now());
        conversation.setCloseReason("USER_ENDED");
        conversation.setClosedBy(userId);
        releaseBoundWorker(conversationId);
        Conversation saved = conversationRepository.save(conversation);
        // Design 5.2: the host session.close push for the torn-down sessions
        // runs on the AFTER_COMMIT listener (SSE frame is the controller's).
        eventPublisher.publishEvent(new ConversationClosedEvent(conversationId, "USER_ENDED"));
        return saved;
    }

    /**
     * Archive a conversation (owner-only {@code POST /{id}/archive};
     * 2026-09-21 close/archive lifecycle design section 4.2) - the soft
     * delete that subsumes the old DELETED. No un-archive.
     *
     * <ul>
     * <li>CLOSED - only the archive trail is stamped
     * ({@code USER_ARCHIVED}); the existing close trail is preserved.</li>
     * <li>ACTIVE - both trails are stamped: the archive trail plus an
     * implicit close with {@code close_reason = ARCHIVED}, and the
     * {@link #releaseBoundWorker} teardown runs.</li>
     * <li>ARCHIVED - idempotent, row returned unchanged.</li>
     * </ul>
     */
    @Transactional
    public Conversation archiveConversation(UUID conversationId, UUID userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));
        if (conversation.getStatus() == Conversation.Status.ARCHIVED) {
            return conversation;
        }
        conversation.setArchivedAt(Instant.now());
        conversation.setArchiveReason("USER_ARCHIVED");
        conversation.setArchivedBy(userId);
        if (conversation.getStatus() == Conversation.Status.CLOSED) {
            conversation.setStatus(Conversation.Status.ARCHIVED);
        } else {
            // Implicit close of a never-closed ACTIVE row: stamp the close
            // trail with ARCHIVED so the row carries a complete history,
            // and run the same teardown an explicit close would have run.
            conversation.setStatus(Conversation.Status.ARCHIVED);
            conversation.setClosedAt(Instant.now());
            conversation.setCloseReason("ARCHIVED");
            if (userId != null) {
                conversation.setClosedBy(userId);
            }
            releaseBoundWorker(conversationId);
            // Design 5.2: host session.close push on the AFTER_COMMIT listener
            // (only the teardown path publishes - a CLOSED archive kept no
            // sessions).
            eventPublisher.publishEvent(
                    new ConversationArchivedEvent(conversationId, "USER_ARCHIVED"));
        }
        return conversationRepository.save(conversation);
    }
    // ------------------------------------------------------------------
    // HITL (Phase 7a)
    // ------------------------------------------------------------------

    /**
     * Append an {@code APPROVAL_REQUEST} row authored by an agent.
     *
     * <p>
     * {@code payloadJson} carries the proposed action (free-form
     * agent-supplied JSON â€” the UI's renderer picks based on shape).
     * {@code expiresAt} bounds the wait; the row flips to
     * {@link ConversationMessage.ApprovalStatus#EXPIRED} when checked
     * past that instant.
     * </p>
     */
    @Transactional
    public ConversationMessage appendApprovalRequest(
            UUID conversationId,
            UUID requestingAgentId,
            String content,
            String payloadJson,
            Instant expiresAt) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        long nextSequence = messageRepository
                .findFirstByConversationIdOrderBySequenceNoDesc(conversationId)
                .map(m -> m.getSequenceNo() + 1)
                .orElse(0L);

        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setSequenceNo(nextSequence);
        message.setRole(ConversationMessage.Role.APPROVAL_REQUEST);
        message.setAuthorAgentId(requestingAgentId);
        message.setContent(content);
        message.setPayloadJson(payloadJson);
        message.setApprovalStatus(ConversationMessage.ApprovalStatus.PENDING);
        message.setExpiresAt(expiresAt);
        message = messageRepository.save(message);

        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        return message;
    }

    /**
     * Apply a human decision (APPROVED / REJECTED) to a pending approval
     * request and append a paired {@code APPROVAL_RESPONSE} row carrying
     * the decider's identity + optional comment.
     *
     * <p>
     * Rejects with {@link IllegalStateException} when the request is
     * missing, of the wrong role, already decided, expired, or when the
     * caller tries to set anything other than APPROVED / REJECTED.
     * </p>
     */
    @Transactional
    public ApprovalDecisionResult submitApprovalDecision(
            UUID conversationId,
            UUID requestMessageId,
            UUID deciderUserId,
            ConversationMessage.ApprovalStatus decision,
            String comment) {
        if (decision != ConversationMessage.ApprovalStatus.APPROVED
                && decision != ConversationMessage.ApprovalStatus.REJECTED) {
            throw new IllegalArgumentException(
                    "Decision must be APPROVED or REJECTED, got " + decision);
        }
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        ConversationMessage request = messageRepository.findById(requestMessageId)
                .orElseThrow(() -> new ResourceNotFoundException("Approval request", requestMessageId));
        if (!conversationId.equals(request.getConversationId())) {
            throw new IllegalStateException(
                    "Approval request " + requestMessageId
                            + " does not belong to conversation " + conversationId);
        }
        if (request.getRole() != ConversationMessage.Role.APPROVAL_REQUEST) {
            throw new IllegalStateException(
                    "Message " + requestMessageId + " is not an APPROVAL_REQUEST");
        }
        ConversationMessage.ApprovalStatus current = request.getApprovalStatus();
        if (current != ConversationMessage.ApprovalStatus.PENDING) {
            throw new IllegalStateException(
                    "Approval " + requestMessageId + " already resolved (status=" + current + ")");
        }
        Instant expires = request.getExpiresAt();
        if (expires != null && Instant.now().isAfter(expires)) {
            // Side-effect: persist the EXPIRED transition so any later
            // viewer/lister sees the resolved state without us having to
            // re-evaluate the clock everywhere.
            request.setApprovalStatus(ConversationMessage.ApprovalStatus.EXPIRED);
            messageRepository.save(request);
            throw new IllegalStateException(
                    "Approval " + requestMessageId + " has expired (expiresAt=" + expires + ")");
        }

        request.setApprovalStatus(decision);
        request.setApproverId(deciderUserId);
        request = messageRepository.save(request);

        // Append the paired response row so the conversation timeline
        // captures who decided + when + with what comment.
        long nextSequence = messageRepository
                .findFirstByConversationIdOrderBySequenceNoDesc(conversationId)
                .map(m -> m.getSequenceNo() + 1)
                .orElse(0L);
        ConversationMessage response = new ConversationMessage();
        response.setConversationId(conversationId);
        response.setSequenceNo(nextSequence);
        response.setRole(ConversationMessage.Role.APPROVAL_RESPONSE);
        response.setAuthorUserId(deciderUserId);
        response.setContent(comment);
        response.setParentMessageId(request.getId());
        response = messageRepository.save(response);

        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        return new ApprovalDecisionResult(request, response);
    }

    /**
     * Lookup helper for the controller â€” returns null when the row is missing.
     */
    @Transactional(readOnly = true)
    public Optional<ConversationMessage> findMessage(UUID messageId) {
        return messageRepository.findById(messageId);
    }

    /**
     * The pending (unanswered, unexpired) {@code INPUT_REQUEST} at the tail of
     * the active branch — the composer-send path routes typed text to it
     * (Copilot semantics: the options dissolve, the text answers the question).
     * Mirrors the mock's {@code pendingInputRequest}: the latest unanswered
     * ask wins; a trailing {@code INPUT_RESPONSE} means the latest ask was
     * already answered.
     */
    @Transactional(readOnly = true)
    public Optional<ConversationMessage> pendingInputRequest(UUID conversationId) {
        List<ConversationMessage> tail = messageRepository
                .findByConversationIdOrderBySequenceNoDesc(conversationId,
                        org.springframework.data.domain.PageRequest.of(0, 200));
        for (ConversationMessage m : tail) {
            if (m.isSuperseded()) {
                continue;
            }
            if (m.getRole() == ConversationMessage.Role.INPUT_RESPONSE) {
                return Optional.empty();
            }
            if (m.getRole() == ConversationMessage.Role.INPUT_REQUEST) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** Minimal JSON string escaping for a single double-quoted string value. */
    private static String escapeJson(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Append an {@code INPUT_REQUEST} row (the ask_user tool's question). The
     * {@code payloadJson} carries the question payload verbatim (question,
     * options, multiple, agentName, digest); the row's {@code approvalStatus}
     * doubles as the PENDING/ANSWERED discriminator so the card collapses on
     * answer. Mirrors {@link #appendApprovalRequest}.
     */
    @Transactional
    public ConversationMessage appendInputRequest(
            UUID conversationId,
            String payloadJson,
            Instant expiresAt) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        long nextSequence = messageRepository
                .findFirstByConversationIdOrderBySequenceNoDesc(conversationId)
                .map(m -> m.getSequenceNo() + 1)
                .orElse(0L);

        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setSequenceNo(nextSequence);
        message.setRole(ConversationMessage.Role.INPUT_REQUEST);
        message.setContent("");
        message.setPayloadJson(payloadJson);
        message.setApprovalStatus(ConversationMessage.ApprovalStatus.PENDING);
        message.setExpiresAt(expiresAt);
        message = messageRepository.save(message);

        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        return message;
    }

    /**
     * Answer a pending {@code INPUT_REQUEST} (the ask_user tool's resume path):
     * mark the request ANSWERED and append the paired {@code INPUT_RESPONSE}
     * row. Returns both rows so the controller can broadcast the request
     * mutation and the response frame in one pass.
     *
     * <p>Rejects with {@link IllegalStateException} when the row is missing,
     * of the wrong role, already answered, or expired.</p>
     */
    @Transactional
    public InputDecisionResult submitInputAnswer(
            UUID conversationId,
            UUID requestMessageId,
            UUID answererUserId,
            String answerDisplay) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        ConversationMessage request = messageRepository.findById(requestMessageId)
                .orElseThrow(() -> new ResourceNotFoundException("Input request", requestMessageId));
        if (!conversationId.equals(request.getConversationId())) {
            throw new IllegalStateException(
                    "Input request " + requestMessageId
                            + " does not belong to conversation " + conversationId);
        }
        if (request.getRole() != ConversationMessage.Role.INPUT_REQUEST) {
            throw new IllegalStateException(
                    "Message " + requestMessageId + " is not an INPUT_REQUEST");
        }
        ConversationMessage.ApprovalStatus current = request.getApprovalStatus();
        if (current != null && current != ConversationMessage.ApprovalStatus.PENDING) {
            throw new IllegalStateException(
                    "Input " + requestMessageId + " already answered (status=" + current + ")");
        }
        Instant expires = request.getExpiresAt();
        if (expires != null && Instant.now().isAfter(expires)) {
            request.setApprovalStatus(ConversationMessage.ApprovalStatus.EXPIRED);
            messageRepository.save(request);
            throw new IllegalStateException(
                    "Input " + requestMessageId + " has expired (expiresAt=" + expires + ")");
        }

        request.setApprovalStatus(ConversationMessage.ApprovalStatus.APPROVED);
        request.setApproverId(answererUserId);
        request = messageRepository.save(request);

        long nextSequence = messageRepository
                .findFirstByConversationIdOrderBySequenceNoDesc(conversationId)
                .map(m -> m.getSequenceNo() + 1)
                .orElse(0L);
        ConversationMessage response = new ConversationMessage();
        response.setConversationId(conversationId);
        response.setSequenceNo(nextSequence);
        response.setRole(ConversationMessage.Role.INPUT_RESPONSE);
        response.setAuthorUserId(answererUserId);
        response.setContent(answerDisplay);
        response.setParentMessageId(request.getId());
        // The card reads { display } from payloadJson (AskCard.parseAnswer).
        response.setPayloadJson("{\"display\":\"" + escapeJson(answerDisplay) + "\"}");
        response = messageRepository.save(response);

        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        return new InputDecisionResult(request, response);
    }

    /** Carries both rows produced by {@link #submitInputAnswer}. */
    public record InputDecisionResult(ConversationMessage request, ConversationMessage response) {
    }

    /**
     * Carries both rows produced by {@link #submitApprovalDecision} so the
     * caller can broadcast the request mutation AND append the response
     * frame from a single call.
     */
    public record ApprovalDecisionResult(
            ConversationMessage request,
            ConversationMessage response) {
    }

    /**
     * Build a {@link ai.myrmec.engine.context.ContextSnapshot} for the Team
     * if the governance profile has {@code CONTEXT_PINNING=ON} (PINNED_AT_START).
     * Returns null for {@code IMMEDIATE_EFFECT} profiles.
     */
    private ai.myrmec.engine.context.ContextSnapshot buildContextSnapshot(UUID teamId) {
        var policy = governancePolicyResolver.resolve(
                ai.myrmec.engine.governance.GovernanceScope.ofTeam(teamId));
        String contextPinning = ai.myrmec.engine.governance.ProductFeature.CONTEXT_PINNING
                .runtimeName(policy.single(ai.myrmec.engine.governance.ProductFeature.CONTEXT_PINNING));

        if (!"PINNED_AT_START".equals(contextPinning)) {
            return null;
        }

        java.util.List<UUID> instructionVersionIds = instructionAssetVersionResolver
                .resolveActiveVersionIdsForTeam(teamId);

        // Knowledge source IDs: mirror the same logic SessionContextAssembler uses
        java.util.List<UUID> knowledgeSourceIds = sessionContextAssembler
                .resolveActiveKnowledgeSourceIds(teamId);

        return new ai.myrmec.engine.context.ContextSnapshot(
                policy.code(),
                instructionVersionIds,
                knowledgeSourceIds);
    }
}
