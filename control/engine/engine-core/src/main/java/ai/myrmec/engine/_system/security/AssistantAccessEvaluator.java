package ai.myrmec.engine._system.security;

import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SpEL access evaluator for assistant-scoped endpoints (#92).
 *
 * <p>V1 resolves the assistant's parent Team and delegates to
 * {@link TeamAccessEvaluator}: Team VIEWER reads, Team EDITOR authors,
 * Team OWNER manages grants. Fine-grained {@code assistant_grants}
 * enforcement (OWNER/EDITOR/VIEWER/USE per-assistant) is a follow-up (#99) and
 * will layer on top of this baseline.</p>
 */
@Component("assistantAccess")
@RequiredArgsConstructor
public class AssistantAccessEvaluator {

    private final AssistantRepository assistantRepository;
    private final TeamAccessEvaluator teamAccess;

    public boolean canView(UUID assistantId, Authentication authentication) {
        UUID teamId = teamIdOf(assistantId);
        return teamId != null && teamAccess.canView(teamId, authentication);
    }

    public boolean canEdit(UUID assistantId, Authentication authentication) {
        UUID teamId = teamIdOf(assistantId);
        return teamId != null && teamAccess.canEdit(teamId, authentication);
    }

    public boolean canOwn(UUID assistantId, Authentication authentication) {
        UUID teamId = teamIdOf(assistantId);
        return teamId != null && teamAccess.canOwn(teamId, authentication);
    }

    private UUID teamIdOf(UUID assistantId) {
        if (assistantId == null) {
            return null;
        }
        return assistantRepository.findById(assistantId)
                .map(Assistant::getTeamId)
                .orElse(null);
    }
}
