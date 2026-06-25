package ai.myrmec.engine.assistant.dto;

import ai.myrmec.engine.assistant.AssistantVersion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full view of an {@link AssistantVersion} (Draft or Published) (#92). */
public record AssistantVersionResponse(
        UUID id,
        UUID assistantId,
        String versionNumber,
        String bumpType,
        String status,
        UUID parentVersionId,
        UUID draftOwnerId,
        UUID agentProfileId,
        UUID agentProfileVersionId,
        String addendum,
        String greetingMessage,
        int maxIdleMinutes,
        Integer maxSessionAgeHours,
        List<String> kbBindings,
        List<String> disabledTools,
        String hitlOverrideMode,
        List<String> usableVia,
        boolean attachmentsEnabled,
        Integer attachmentRetentionTtl,
        Integer attachmentMaxFileSize,
        String attachmentTypeAllowlist,
        Instant publishedAt,
        UUID publishedBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static AssistantVersionResponse from(AssistantVersion v) {
        return new AssistantVersionResponse(
                v.getId(),
                v.getAssistantId(),
                v.getVersionNumber(),
                v.getBumpType() == null ? null : v.getBumpType().name(),
                v.getStatus() == null ? null : v.getStatus().name(),
                v.getParentVersionId(),
                v.getDraftOwnerId(),
                v.getAgentProfileId(),
                v.getAgentProfileVersionId(),
                v.getAddendum(),
                v.getGreetingMessage(),
                v.getMaxIdleMinutes(),
                v.getMaxSessionAgeHours(),
                v.getKbBindings(),
                v.getDisabledTools(),
                v.getHitlOverrideMode() == null ? null : v.getHitlOverrideMode().name(),
                v.getUsableVia(),
                v.isAttachmentsEnabled(),
                v.getAttachmentRetentionTtl(),
                v.getAttachmentMaxFileSize(),
                v.getAttachmentTypeAllowlist(),
                v.getPublishedAt(),
                v.getPublishedBy(),
                v.getCreatedAt(),
                v.getUpdatedAt()
        );
    }
}
