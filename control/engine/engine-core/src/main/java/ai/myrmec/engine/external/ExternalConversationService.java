package ai.myrmec.engine.external;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine._system.security.ServiceAccountPrincipal;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationMessageRepository;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.dispatch.ConversationTurnDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * External-API ({@code /api/v1/external/**}) conversation orchestration for #95.
 *
 * <p>Wraps the internal {@link ConversationService} with the service-account
 * boundary: conversations are created as {@link Conversation.Source#EXTERNAL_API},
 * tagged with the opening service account and the caller's opaque
 * {@code externalUserRef}, and every read/write re-asserts that the conversation
 * belongs to the calling service account (and its project) so one integration
 * can never touch another's threads. Assistant access is gated up front by
 * {@link ExternalAssistantService#requireUsable}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalConversationService {

    private final ExternalAssistantService externalAssistantService;
    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final ConversationTurnDispatcher turnDispatcher;

    /**
     * Start a new external conversation against an assistant the service account
     * may use. Pins the assistant's published version (via the internal service),
     * stamps the external provenance, and optionally posts a first user turn.
     */
    @Transactional
    public Conversation startConversation(ServiceAccountPrincipal principal,
                                          UUID assistantId,
                                          String externalUserRef,
                                          String firstMessage) {
        String endUserRef = normaliseRequiredRef(externalUserRef);
        externalAssistantService.requireUsable(
                principal.getServiceAccountId(), principal.getProjectId(), assistantId);

        Conversation conversation = conversationService.createConversation(
                principal.getProjectId(), null, null, null, null, assistantId);
        conversation.setSource(Conversation.Source.EXTERNAL_API);
        conversation.setExternalUserRef(endUserRef);
        conversation.setServiceAccountId(principal.getServiceAccountId());
        conversation = conversationRepository.save(conversation);

        if (firstMessage != null && !firstMessage.isBlank()) {
            appendUserTurn(conversation.getId(), firstMessage);
        }
        log.info("External conversation {} started by service account {} (assistant {}, endUserRef {})",
                conversation.getId(), principal.getServiceAccountId(), assistantId, endUserRef);
        return conversation;
    }

    /** Post a user turn to an owned conversation and dispatch it best-effort. */
    @Transactional
    public ConversationMessage postMessage(ServiceAccountPrincipal principal,
                                           UUID conversationId,
                                           String content) {
        Conversation conversation = requireOwnedConversation(principal, conversationId);
        if (conversation.getStatus() != Conversation.Status.ACTIVE) {
            throw new BadRequestException("Conversation is closed and cannot accept new messages.");
        }
        if (content == null || content.isBlank()) {
            throw BadRequestException.requiredField("content");
        }
        return appendUserTurn(conversationId, content);
    }

    /**
     * Messages of an owned conversation strictly newer than {@code since}
     * (sequence_no &gt; since), ascending. Pass {@code null} for the full
     * transcript. Drives the {@code ?since=} incremental poll.
     */
    @Transactional(readOnly = true)
    public List<ConversationMessage> listMessages(ServiceAccountPrincipal principal,
                                                  UUID conversationId,
                                                  Long since) {
        requireOwnedConversation(principal, conversationId);
        if (since == null) {
            return messageRepository.findByConversationIdOrderBySequenceNoAsc(conversationId);
        }
        return messageRepository
                .findByConversationIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(conversationId, since);
    }

    /** Close an owned conversation (idempotent): flips it to ARCHIVED. */
    @Transactional
    public Conversation closeConversation(ServiceAccountPrincipal principal, UUID conversationId) {
        Conversation conversation = requireOwnedConversation(principal, conversationId);
        if (conversation.getStatus() != Conversation.Status.ARCHIVED) {
            conversation.setStatus(Conversation.Status.ARCHIVED);
            conversation = conversationRepository.save(conversation);
        }
        return conversation;
    }

    /**
     * Conversations opened by this service account, optionally filtered to one
     * end-user reference, most recently updated first.
     */
    @Transactional(readOnly = true)
    public List<Conversation> listConversations(ServiceAccountPrincipal principal, String externalUserRef) {
        UUID serviceAccountId = principal.getServiceAccountId();
        if (externalUserRef == null || externalUserRef.isBlank()) {
            return conversationRepository.findByServiceAccountIdOrderByUpdatedAtDesc(serviceAccountId);
        }
        return conversationRepository
                .findByServiceAccountIdAndExternalUserRefOrderByUpdatedAtDesc(
                        serviceAccountId, externalUserRef.trim());
    }

    /** Fetch one owned conversation (for {@code GET /external/conversations/{id}}). */
    @Transactional(readOnly = true)
    public Conversation getConversation(ServiceAccountPrincipal principal, UUID conversationId) {
        return requireOwnedConversation(principal, conversationId);
    }

    /**
     * Resolve a conversation and assert it belongs to the calling service account
     * (external provenance + same service account + same project). Any mismatch
     * is reported as 404 so a caller cannot probe other accounts' conversations.
     */
    private Conversation requireOwnedConversation(ServiceAccountPrincipal principal, UUID conversationId) {
        Conversation conversation = conversationId == null
                ? null
                : conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null
                || conversation.getSource() != Conversation.Source.EXTERNAL_API
                || !Objects.equals(conversation.getServiceAccountId(), principal.getServiceAccountId())
                || !Objects.equals(conversation.getProjectId(), principal.getProjectId())) {
            throw ResourceNotFoundException.of("Conversation", String.valueOf(conversationId));
        }
        return conversation;
    }

    /** Append a USER message and best-effort dispatch the turn to an agent. */
    private ConversationMessage appendUserTurn(UUID conversationId, String content) {
        ConversationMessage saved = conversationService.appendMessage(
                conversationId, ConversationMessage.Role.USER, content, null, null);
        try {
            turnDispatcher.dispatch(conversationId);
        } catch (Exception e) {
            log.warn("External turn dispatch threw for conversation {}: {}", conversationId, e.getMessage(), e);
        }
        return saved;
    }

    private static String normaliseRequiredRef(String externalUserRef) {
        if (externalUserRef == null || externalUserRef.isBlank()) {
            throw BadRequestException.requiredField("X-Myrmec-End-User-Ref");
        }
        return externalUserRef.trim();
    }
}
