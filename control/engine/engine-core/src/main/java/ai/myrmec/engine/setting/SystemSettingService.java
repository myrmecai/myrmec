package ai.myrmec.engine.setting;

import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * #71a &mdash; typed read/write access to the platform
 * {@link SystemSetting} store, plus an admin mutation path that
 * validates by {@link SettingType} and records an audit row.
 *
 * <p>Read accessors ({@link #getInt}, {@link #getRatio}, etc.) take a
 * caller-supplied default that is returned when the key is absent or
 * its value is blank/unparseable &mdash; so a missing or "unset" row is
 * never fatal and consumers can be wired incrementally. Only the admin
 * {@link #update} path performs strict validation; reads are forgiving
 * by design.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSettingService {

    /** Audit action for a settings change. */
    public static final String AUDIT_ACTION = AuditAction.SETTING_CHANGED;

    private final SystemSettingRepository repository;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    // ---- read paths -------------------------------------------------

    @Transactional(readOnly = true)
    public List<SystemSetting> findAll() {
        return repository.findAllByOrderByKeyAsc();
    }

    @Transactional(readOnly = true)
    public Optional<SystemSetting> find(String key) {
        return repository.findById(key);
    }

    /** Raw string value, or {@code defaultValue} when unset/blank. */
    @Transactional(readOnly = true)
    public String getString(String key, String defaultValue) {
        return find(key)
                .map(SystemSetting::getValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(defaultValue);
    }

    /** Parsed {@code long}, or {@code defaultValue} when unset/unparseable. */
    @Transactional(readOnly = true)
    public long getInt(String key, long defaultValue) {
        String raw = getString(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("system setting {} is not a valid INT ('{}'); using default {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    /** Parsed ratio in [0,1], or {@code defaultValue} when unset/invalid. */
    @Transactional(readOnly = true)
    public double getRatio(String key, double defaultValue) {
        String raw = getString(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (v < 0.0 || v > 1.0) {
                log.warn("system setting {} ratio out of range ('{}'); using default {}",
                        key, raw, defaultValue);
                return defaultValue;
            }
            return v;
        } catch (NumberFormatException e) {
            log.warn("system setting {} is not a valid RATIO ('{}'); using default {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    /** Parsed boolean, or {@code defaultValue} when unset. */
    @Transactional(readOnly = true)
    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = getString(key, null);
        if (raw == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    // ---- write path -------------------------------------------------

    /**
     * Update the value of an existing setting. The key must already
     * exist (settings are provisioned by migrations, not created
     * ad-hoc); the supplied value is validated against the stored
     * {@link SettingType}. Records an audit row on success.
     *
     * @throws ResourceNotFoundException if the key does not exist
     * @throws BadRequestException       if the value fails type validation
     */
    @Transactional
    public SystemSetting update(String key, String newValue, UUID actorUserId) {
        SystemSetting setting = repository.findById(key)
                .orElseThrow(() -> ResourceNotFoundException.of("SystemSetting", key));

        String normalised = newValue == null ? "" : newValue.trim();
        validate(setting.getValueType(), normalised);

        String oldValue = setting.getValue();
        setting.setValue(normalised);
        setting.setUpdatedBy(actorUserId);
        SystemSetting saved = repository.save(setting);

        /* Audit the setting change */
        auditEventService.recordEvent(
                ResourceType.SYSTEM_SETTING, null, AUDIT_ACTION,
                "ORGANIZATION", null,
                actorUserId, actorUserId != null ? "USER" : "SYSTEM",
                null, null, null, null,
                Map.of(
                        "key", key,
                        "oldValue", oldValue == null ? "" : oldValue,
                        "newValue", normalised));

        return saved;
    }

    /**
     * Validate a candidate value against a {@link SettingType}. A blank
     * value is always accepted (means "unset, use the code default").
     */
    private void validate(SettingType type, String value) {
        if (value.isBlank()) {
            return;
        }
        switch (type) {
            case INT -> {
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw BadRequestException.forField(
                            "value", "INVALID_FORMAT", "Value must be an integer.");
                }
            }
            case RATIO -> {
                double v;
                try {
                    v = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw BadRequestException.forField(
                            "value", "INVALID_FORMAT", "Value must be a number between 0 and 1.");
                }
                if (v < 0.0 || v > 1.0) {
                    throw BadRequestException.forField(
                            "value", "INVALID_VALUE", "Value must be between 0 and 1.");
                }
            }
            case BOOL -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw BadRequestException.forField(
                            "value", "INVALID_VALUE", "Value must be 'true' or 'false'.");
                }
            }
            case JSON -> {
                try {
                    objectMapper.readTree(value);
                } catch (Exception e) {
                    throw BadRequestException.forField(
                            "value", "INVALID_FORMAT", "Value must be valid JSON.");
                }
            }
            case STRING, MODEL_REF -> {
                // Free text / loose model-code reference; no structural check.
            }
        }
    }
}
