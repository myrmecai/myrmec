package ai.myrmec.engine.conversation;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.assistant.AssistantVersion;
import ai.myrmec.engine.assistant.AssistantVersionRepository;
import ai.myrmec.engine.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates conversation creation, ACL, and message appending.
 *
 * <p>Sequence numbers are assigned <em>here</em> rather than by the
 * database because the engine streams the same sequence_no to all WS
 * viewers and would otherwise need a round-trip to learn what the
 * database picked. Acquiring a row-level lock on the parent conversation
 * row in {@link #appendMessage} serialises concurrent writers per
 * conversation while leaving cross-conversation throughput unaffected.</p>
 *
 * <p>Phase 6a only persists; the streaming broker (6c) and context-window
 * manager (6d) layer on top.</p>
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final ConversationParticipantRepository participantRepository;
    private final AuditLogService auditLogService;
    private final AssistantRepository assistantRepository;
    private final AssistantVersionRepository assistantVersionRepository;
    private final AgentHostRepository agentHostRepository;
    @org.springframework.context.annotation.Lazy
    private final ai.myrmec.engine.agent.AgentRepository agentInstanceRepository;
    @org.springframework.context.annotation.Lazy
    private final ai.myrmec.engine.agent.AgentService agentService;

    /** Create a new conversation and seed an OWNER participant row. */
    @Transactional
    public Conversation createConversation(
            UUID projectId,
            UUID createdBy,
            String title) {
        return createConversation(projectId, createdBy, title, null, null);
    }

    /**
     * Full-form create — used by the REST endpoint. {@code agentId} is
     * optional (pinned when null on first agent turn); same for
     * {@code systemPromptOverride}.
     */
    @Transactional
    public Conversation createConversation(
            UUID projectId,
            UUID createdBy,
            String title,
            UUID agentId,
            String systemPromptOverride) {
        return createConversation(projectId, createdBy, title, agentId, systemPromptOverride, null);
    }

    /**
     * Assistant-aware create (#92). When {@code assistantId} is supplied the
     * assistant's currently published version is pinned onto the new
     * conversation for the life of the session (assistant-entity.md §5.6).
     *
     * <p>The parent assistant row is taken under a pessimistic write lock so
     * that a concurrent publish cannot race the version we pin: we read
     * {@code current_version_id} and copy it (plus the forward-seam
     * {@code agent_profile_version_id}) onto the conversation in the same
     * transaction. Any publish that commits after us bumps a new version but
     * leaves this conversation pinned to the snapshot it started with.</p>
     */
    @Transactional
    public Conversation createConversation(
            UUID projectId,
            UUID createdBy,
            String title,
            UUID agentId,
            String systemPromptOverride,
            UUID assistantId) {
        Conversation conversation = new Conversation();
        conversation.setProjectId(projectId);
        conversation.setCreatedBy(createdBy);
        conversation.setTitle(title);
        conversation.setAgentId(agentId);
        conversation.setSystemPromptOverride(systemPromptOverride);
        conversation.setStatus(Conversation.Status.ACTIVE);

        if (assistantId != null) {
            pinAssistantVersion(conversation, projectId, assistantId);
        }

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
     * Resolve and pin the assistant's published version under a row lock.
     * Mutates {@code conversation} in place; never persists it.
     */
    private void pinAssistantVersion(Conversation conversation, UUID projectId, UUID assistantId) {
        Assistant assistant = assistantRepository.findByIdForUpdate(assistantId)
                .orElseThrow(() -> ResourceNotFoundException.of("Assistant", assistantId.toString()));

        if (projectId != null && !projectId.equals(assistant.getProjectId())) {
            throw new BadRequestException("Assistant does not belong to the requested project.");
        }
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

        conversation.setAssistantId(assistant.getId());
        conversation.setAssistantVersionId(version.getId());
        conversation.setAgentProfileVersionId(version.getAgentProfileVersionId());

        // The conversation needs a runtime AgentHost to dispatch turns, but the
        // assistant only pins an agent *profile*. Resolve an active host for
        // that profile (preferring one scoped to this project, then an
        // unscoped/system host) when the caller did not pin one explicitly.
        // Leaving it null is non-fatal — the session is created and the turn
        // dispatcher simply declines until a host becomes available.
        if (conversation.getAgentId() == null && version.getAgentProfileId() != null) {
            resolveActiveAgentHost(version.getAgentProfileId(), projectId)
                    .ifPresent(conversation::setAgentId);
        }
    }

    /**
     * Pick an active {@link AgentHost} for the given profile, preferring a host
     * scoped to {@code projectId}, then a system-wide (unscoped) host, then any
     * active host. Returns the host id, or empty when none are active.
     */
    private Optional<UUID> resolveActiveAgentHost(UUID profileId, UUID projectId) {
        List<AgentHost> hosts = agentHostRepository.findActiveByProfileId(profileId);
        if (hosts.isEmpty()) {
            return Optional.empty();
        }
        return hosts.stream()
                .filter(h -> projectId != null && projectId.equals(h.getProjectId()))
                .findFirst()
                .or(() -> hosts.stream().filter(h -> h.getProjectId() == null).findFirst())
                .or(() -> hosts.stream().findFirst())
                .map(AgentHost::getId);
    }

    @Transactional
    public ConversationParticipant addParticipant(
            UUID conversationId,
            UUID userId,
            ConversationParticipant.Role role) {
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        Optional<ConversationParticipant> existing =
                participantRepository.findByConversationIdAndUserId(conversationId, userId);
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
        // lists. Heuristic only — a summarizer-model title (the
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
     * row (#86) — e.g. the "no agent online" message shown when a turn cannot be
     * dispatched because no warm worker is available. Like
     * {@link #appendContextSummary} it bypasses the auto-title heuristic and
     * records {@code payload_json} so context assembly can recognise (and skip)
     * the marker, keeping the contradictory notice out of the agent's history
     * window once a worker does come online.
     *
     * @param payloadJson serialised marker (e.g. {@code {"kind":"NO_AGENT_NOTICE"}})
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
        return cut.strip() + "…";
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
     * <p>A {@code null} or blank {@code rating} clears any existing
     * feedback (un-rates the message); a non-null rating is parsed
     * case-insensitively to {@link ConversationMessage.Rating} and an
     * unknown value is rejected. Every mutation is attributed to
     * {@code actorUserId} and audited.</p>
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
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .action(parsed == null ? "CONVERSATION_FEEDBACK_CLEARED" : "CONVERSATION_FEEDBACK_SET")
                .actorUserId(actorUserId)
                .resourceType("ConversationMessage")
                .resourceId(messageId)
                .scopeType("CONVERSATION")
                .scopeId(conversationId)
                .payload(payload)
                .build());
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
     * truncated at the edited message — every non-superseded row from that
     * message's {@code sequence_no} onward is soft-superseded (retained, not
     * destroyed) — and a fresh USER message carrying {@code newContent} is
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
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .action("CONVERSATION_TURN_EDITED")
                .actorUserId(actorUserId)
                .resourceType("ConversationMessage")
                .resourceId(replacement.getId())
                .scopeType("CONVERSATION")
                .scopeId(conversationId)
                .payload(payload)
                .build());
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
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .action("CONVERSATION_TURN_REGENERATED")
                .actorUserId(actorUserId)
                .resourceType("ConversationMessage")
                .resourceId(messageId)
                .scopeType("CONVERSATION")
                .scopeId(conversationId)
                .payload(payload)
                .build());
        return assistant;
    }

    /** Load a message, asserting it belongs to the conversation and is on the
     * active (non-superseded) branch. */
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
        List<ConversationMessage> messages =
                messageRepository.findByConversationIdOrderBySequenceNoAsc(conversationId);

        String title = (conversation.getTitle() == null || conversation.getTitle().isBlank())
                ? "Conversation" : conversation.getTitle().trim();
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("_Exported ").append(Instant.now()).append(" · ")
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
                        .append(m.getFeedbackRating() == ConversationMessage.Rating.UP ? "👍" : "👎");
                if (m.getFeedbackReason() != null && !m.getFeedbackReason().isBlank()) {
                    md.append(" — ").append(m.getFeedbackReason());
                }
                md.append("\n\n");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("format", "markdown");
        payload.put("messageCount", messages.size());
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .action("CONVERSATION_EXPORTED")
                .actorUserId(actorUserId)
                .resourceType("Conversation")
                .resourceId(conversationId)
                .scopeType("CONVERSATION")
                .scopeId(conversationId)
                .payload(payload)
                .build());
        return md.toString();
    }

    /**
     * List conversations under a project ordered by most-recently-updated
     * first. Used by the chat sidebar to show the user the threads they
     * can resume.
     */
    @Transactional(readOnly = true)
    public List<Conversation> listByProject(UUID projectId) {
        return conversationRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
    }

    /**
     * Release any worker still BOUND to this conversation back to the warm
     * pool (§9.5). Called when the conversation is archived or closed.
     */
    private void releaseBoundWorker(UUID conversationId) {
        try {
            agentInstanceRepository
                    .findByConversationIdAndStatus(conversationId, ai.myrmec.engine.agent.Agent.Status.BOUND)
                    .ifPresent(instance -> {
                        agentService.releaseInstance(instance.getId());
                        log.info("Released BOUND worker {} for archived conversation {}",
                                instance.getId(), conversationId);
                    });
        } catch (Exception e) {
            log.warn("Failed to release BOUND worker for conversation {}: {}",
                    conversationId, e.getMessage());
        }
    }

    /**
     * Apply an owner-editable partial update (rename / archive / unarchive)
     * from the chat {@code ⋯} menu. A {@code null} argument leaves that
     * field untouched.
     *
     * <p>Only {@link Conversation.Status#ACTIVE} and
     * {@link Conversation.Status#ARCHIVED} are reachable here — the
     * destructive {@code DELETED} transition is rejected so an archive
     * action can never accidentally tombstone a thread.</p>
     */
    @Transactional
    public Conversation updateConversation(UUID conversationId, String title, String status) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        if (title != null) {
            conversation.setTitle(title);
        }
        if (status != null) {
            Conversation.Status target;
            try {
                target = Conversation.Status.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown conversation status: " + status);
            }
            if (target != Conversation.Status.ACTIVE && target != Conversation.Status.ARCHIVED) {
                throw new IllegalArgumentException(
                        "Status may only be set to ACTIVE or ARCHIVED, got " + target);
            }
            conversation.setStatus(target);
            // §9.5 — when a conversation is archived, release any BOUND worker
            // back to the warm pool so it can serve other conversations.
            if (target == Conversation.Status.ARCHIVED) {
                releaseBoundWorker(conversationId);
            }
        }
        return conversationRepository.save(conversation);
    }

    // ------------------------------------------------------------------
    // HITL (Phase 7a)
    // ------------------------------------------------------------------

    /**
     * Append an {@code APPROVAL_REQUEST} row authored by an agent.
     *
     * <p>{@code payloadJson} carries the proposed action (free-form
     * agent-supplied JSON — the UI's renderer picks based on shape).
     * {@code expiresAt} bounds the wait; the row flips to
     * {@link ConversationMessage.ApprovalStatus#EXPIRED} when checked
     * past that instant.</p>
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
     * <p>Rejects with {@link IllegalStateException} when the request is
     * missing, of the wrong role, already decided, expired, or when the
     * caller tries to set anything other than APPROVED / REJECTED.</p>
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

    /** Lookup helper for the controller — returns null when the row is missing. */
    @Transactional(readOnly = true)
    public Optional<ConversationMessage> findMessage(UUID messageId) {
        return messageRepository.findById(messageId);
    }
    /**
     * Carries both rows produced by {@link #submitApprovalDecision} so the
     * caller can broadcast the request mutation AND append the response
     * frame from a single call.
     */
    public record ApprovalDecisionResult(
            ConversationMessage request,
            ConversationMessage response) { }
}
