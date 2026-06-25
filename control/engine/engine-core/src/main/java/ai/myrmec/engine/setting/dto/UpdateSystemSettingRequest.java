package ai.myrmec.engine.setting.dto;

import jakarta.validation.constraints.Size;

/**
 * #71a &mdash; admin request to update a single setting's value. A null
 * or blank value resets the setting to "unset" (code-side default).
 */
public record UpdateSystemSettingRequest(
        @Size(max = 10_000, message = "Value must be at most 10000 characters.")
        String value) {
}
