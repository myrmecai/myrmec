package ai.myrmec.engine.assistant.dto;

import jakarta.validation.constraints.Size;

/**
 * Zone-1 parent-row edit (#92, §5.3): name/description in place, no version
 * bump. Null fields are left unchanged.
 */
public record UpdateAssistantRequest(
        @Size(max = 120) String name,
        @Size(max = 4000) String description
) {
}
