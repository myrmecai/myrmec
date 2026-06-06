package ai.myrmec.engine._system.security;

import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationParticipant;
import ai.myrmec.engine.conversation.ConversationParticipantRepository;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.user.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SpEL access evaluator for conversation-scoped endpoints.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Direct participant grant on this conversation (OWNER/EDITOR/VIEWER).</li>
 *   <li>Fall back to {@link ProjectAccessEvaluator} on the parent project — so
 *       project owners and editors can always see/post into conversations under
 *       their projects even if no explicit participant row exists.</li>
 * </ol>
 *
 * <p>{@code canOwn} requires either a participant OWNER row or governance bypass
 * via ORG_ADMIN.</p>
 */
@Component("conversationAccess")
@RequiredArgsConstructor
public class ConversationAccessEvaluator {

    private static final Set<ConversationParticipant.Role> VIEWER_ROLES =
            Set.of(ConversationParticipant.Role.OWNER,
                    ConversationParticipant.Role.EDITOR,
                    ConversationParticipant.Role.VIEWER);
    private static final Set<ConversationParticipant.Role> EDITOR_ROLES =
            Set.of(ConversationParticipant.Role.OWNER,
                    ConversationParticipant.Role.EDITOR);

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final ProjectAccessEvaluator projectAccess;

    public boolean canView(UUID conversationId, Authentication authentication) {
        return hasAccess(conversationId, authentication, VIEWER_ROLES, true);
    }

    public boolean canEdit(UUID conversationId, Authentication authentication) {
        return hasAccess(conversationId, authentication, EDITOR_ROLES, false);
    }

    /** Owner-only operations (rename, archive, delete). */
    public boolean canOwn(UUID conversationId, Authentication authentication) {
        UserPrincipal user = principalOf(authentication);
        if (user == null || conversationId == null) return false;

        Optional<ConversationParticipant> p =
                participantRepository.findByConversationIdAndUserId(conversationId, user.getUserId());
        if (p.isPresent() && p.get().getRole() == ConversationParticipant.Role.OWNER) {
            return true;
        }
        return user.isOrgAdmin();
    }

    private boolean hasAccess(
            UUID conversationId,
            Authentication authentication,
            Set<ConversationParticipant.Role> roles,
            boolean allowProjectViewer) {
        UserPrincipal user = principalOf(authentication);
        if (user == null || conversationId == null) return false;

        // 1. Direct participant row.
        Optional<ConversationParticipant> p =
                participantRepository.findByConversationIdAndUserId(conversationId, user.getUserId());
        if (p.isPresent() && roles.contains(p.get().getRole())) {
            return true;
        }

        // 2. Fall back to project ACL on the parent.
        Optional<Conversation> conv = conversationRepository.findById(conversationId);
        if (conv.isEmpty()) return false;
        UUID projectId = conv.get().getProjectId();
        return allowProjectViewer
                ? projectAccess.canView(projectId, authentication)
                : projectAccess.canEdit(projectId, authentication);
    }

    private static UserPrincipal principalOf(Authentication authentication) {
        if (authentication == null) return null;
        Object p = authentication.getPrincipal();
        return p instanceof UserPrincipal up ? up : null;
    }
}
