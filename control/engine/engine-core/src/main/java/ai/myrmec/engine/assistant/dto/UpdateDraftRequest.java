package ai.myrmec.engine.assistant.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Zone-2 Draft edit (#92, §4): version-row behaviour fields. Every field is
 * optional — null leaves the current Draft value unchanged. Applied only to an
 * open Draft; Published versions are frozen.
 */
public record UpdateDraftRequest(
        @Size(max = 8000) String addendum,
        @Size(max = 8000) String greetingMessage,
        Integer maxIdleMinutes,
        Integer maxSessionAgeHours,
        List<String> kbBindings,
        List<String> disabledTools,
        String hitlOverrideMode,
        List<String> usableVia,
        Boolean attachmentsEnabled,
        Integer attachmentRetentionTtl,
        Integer attachmentMaxFileSize,
        String attachmentTypeAllowlist
) {
}
