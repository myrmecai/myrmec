package ai.myrmec.engine.setting.dto;

import ai.myrmec.engine.setting.SettingType;
import ai.myrmec.engine.setting.SystemSetting;

import java.time.Instant;
import java.util.UUID;

/**
 * #71a &mdash; read projection of a {@link SystemSetting} row.
 */
public record SystemSettingResponse(
        String key,
        SettingType valueType,
        String value,
        String description,
        UUID updatedBy,
        Instant updatedAt) {

    public static SystemSettingResponse from(SystemSetting s) {
        return new SystemSettingResponse(
                s.getKey(),
                s.getValueType(),
                s.getValue(),
                s.getDescription(),
                s.getUpdatedBy(),
                s.getUpdatedAt());
    }
}
