package ai.myrmec.engine.assistant.dto;

import ai.myrmec.engine.assistant.AssistantGrant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Add one ACL entry to an assistant (#92, §6). */
public record AddGrantRequest(
        @NotNull AssistantGrant.PrincipalType principalType,
        @NotBlank @Size(max = 255) String principalId,
        @NotNull AssistantGrant.Permission permission
) {
}
