package ai.myrmec.engine.conversation;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
        Conversation conversation = new Conversation();
        conversation.setProjectId(projectId);
        conversation.setCreatedBy(createdBy);
        conversation.setTitle(title);
        conversation.setAgentId(agentId);
        conversation.setSystemPromptOverride(systemPromptOverride);
        conversation.setStatus(Conversation.Status.ACTIVE);
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

        // Bump conversation.updated_at so list views can sort by recency.
        conversation.setUpdatedAt(java.time.Instant.now());
        conversationRepository.save(conversation);
        return message;
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> listMessages(UUID conversationId) {
        return messageRepository.findByConversationIdOrderBySequenceNoAsc(conversationId);
    }

    @Transactional(readOnly = true)
    public Conversation findById(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));
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
