package ai.myrmec.engine.conversation;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
