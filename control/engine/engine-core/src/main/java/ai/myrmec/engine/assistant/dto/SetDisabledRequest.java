package ai.myrmec.engine.assistant.dto;

import jakarta.validation.constraints.NotNull;

/** Toggle the reversible disabled kill switch on an assistant (#92, §7). */
public record SetDisabledRequest(
        @NotNull Boolean disabled
) {
}
