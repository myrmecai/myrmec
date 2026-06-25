package ai.myrmec.engine.assistant.dto;

import ai.myrmec.engine.assistant.AssistantGrant;

import java.time.Instant;
import java.util.UUID;

/** View of one {@link AssistantGrant} ACL entry (#92, §6). */
public record AssistantGrantResponse(
        UUID id,
        UUID assistantId,
        String principalType,
        String principalId,
        String permission,
        Instant grantedAt,
        UUID grantedBy
) {
    public static AssistantGrantResponse from(AssistantGrant g) {
        return new AssistantGrantResponse(
                g.getId(),
                g.getAssistantId(),
                g.getPrincipalType() == null ? null : g.getPrincipalType().name(),
                g.getPrincipalId(),
                g.getPermission() == null ? null : g.getPermission().name(),
                g.getGrantedAt(),
                g.getGrantedBy()
        );
    }
}
