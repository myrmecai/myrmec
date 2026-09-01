// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.project;

import ai.myrmec.engine._system.common.AuditReason;
import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.governance.EffectivePolicy;
import ai.myrmec.engine.governance.GovernancePolicyResolver;
import ai.myrmec.engine.governance.GovernanceScope;
import ai.myrmec.engine.governance.ProductFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ProjectSettingService {

    private final ProjectSettingRepository repository;

    /** @Lazy to break the circular dependency: ProjectSettingService → AuditEventService → GovernancePolicyResolver → SystemSettingService → AuditEventService */
    @Lazy
    private final AuditEventService auditEventService;

    @Lazy
    private final GovernancePolicyResolver governancePolicyResolver;

    /**
     * Self-injection (@Lazy) so the REQUIRES_NEW on {@link #recordSettingChange}
     * is honoured — a plain {@code this.recordSettingChange(...)} call bypasses
     * the Spring proxy and the propagation would be ignored.
     */
    @Lazy
    private ProjectSettingService self;

    /** Well-known project setting key for audit integrity opt-in. */
    public static final String AUDIT_INTEGRITY_SETTING_KEY = "audit_integrity_enabled";

    public ProjectSettingService(
            ProjectSettingRepository repository,
            @Lazy AuditEventService auditEventService,
            @Lazy GovernancePolicyResolver governancePolicyResolver,
            @Lazy ProjectSettingService self) {
        this.repository = repository;
        this.auditEventService = auditEventService;
        this.governancePolicyResolver = governancePolicyResolver;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<ProjectSetting> findAll(UUID projectId) {
        return repository.findByProjectIdOrderBySettingKeyAsc(projectId);
    }

    @Transactional(readOnly = true)
    public ProjectSetting find(UUID projectId, String key) {
        return repository.findByProjectIdAndSettingKey(projectId, key)
                .orElseThrow(() -> ResourceNotFoundException.of("ProjectSetting", key));
    }

    @Transactional(readOnly = true)
    public String getString(UUID projectId, String key, String defaultValue) {
        return repository.findByProjectIdAndSettingKey(projectId, key)
                .map(ProjectSetting::getSettingValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(defaultValue);
    }

    @Transactional(readOnly = true)
    public int getInt(UUID projectId, String key, int defaultValue) {
        String raw = getString(projectId, key, null);
        if (raw == null) return defaultValue;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    @Transactional(readOnly = true)
    public boolean getBoolean(UUID projectId, String key, boolean defaultValue) {
        String raw = getString(projectId, key, null);
        if (raw == null) return defaultValue;
        return Boolean.parseBoolean(raw.trim());
    }

    @Transactional
    public ProjectSetting update(UUID projectId, String key, String value, UUID updatedBy) {
        // Reject disabling audit integrity under STRICT governance profile
        if (AUDIT_INTEGRITY_SETTING_KEY.equals(key) && "false".equalsIgnoreCase(value)) {
            EffectivePolicy policy = governancePolicyResolver.resolve(GovernanceScope.ofProject(projectId));
            String ceiling = policy.single(ProductFeature.AUDIT_INTEGRITY);
            if ("ON".equals(ceiling)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Cannot disable audit integrity under STRICT governance profile");
            }
        }

        String oldValue = repository.findByProjectIdAndSettingKey(projectId, key)
                .map(ProjectSetting::getSettingValue)
                .orElse(null);

        ProjectSetting setting = repository.findByProjectIdAndSettingKey(projectId, key)
                .orElseGet(() -> {
                    ProjectSetting s = new ProjectSetting();
                    s.setProjectId(projectId);
                    s.setSettingKey(key);
                    s.setValueType("STRING");
                    return s;
                });
        setting.setSettingValue(value);
        setting.setUpdatedBy(updatedBy);
        setting = repository.save(setting);

        // Ratchet on audit integrity setting change (D7):
        // When audit_integrity_enabled transitions, write a genesis/deactivation
        // event into the audit log itself — chaining can't be silently changed.
        if (AUDIT_INTEGRITY_SETTING_KEY.equals(key) && !java.util.Objects.equals(oldValue, value)) {
            boolean turningOn = "true".equalsIgnoreCase(value);
            String eventType = turningOn ? AuditAction.AUDIT_CHAIN_ACTIVATED : AuditAction.AUDIT_CHAIN_DEACTIVATED;
            auditEventService.recordEvent(
                    ResourceType.AUDIT_CHAIN, projectId, eventType,
                    "PROJECT", projectId,
                    updatedBy, "SYSTEM",
                    null, AuditReason.SETTING_CHANGED,
                    Map.of("key", key, "oldValue", oldValue == null ? "null" : oldValue),
                    Map.of("key", key, "newValue", value),
                    null);
            log.info("Audit chain {} for project {}", eventType, projectId);
        } else if (!java.util.Objects.equals(oldValue, value)) {
            // #121 — field-level project-setting change audit: record any other
            // setting transition with old/new so an auditor sees who changed what.
            // Routed through the proxy so REQUIRES_NEW applies (see self above).
            self.recordSettingChange(projectId, key, oldValue, value);
        }

        return setting;
    }

    /**
     * Record a {@code PROJECT_SETTING_CHANGED} audit event in its own
     * transaction (REQUIRES_NEW). A failure here must not roll back the
     * caller's setting change — and because the event commits in a *new*
     * transaction, an FK problem cannot mark the caller's tx rollback-only.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordSettingChange(UUID projectId, String key, String oldValue, String newValue) {
        try {
            auditEventService.recordEvent(
                    ResourceType.PROJECT, projectId, AuditAction.SETTING_CHANGED,
                    "PROJECT", projectId,
                    null, "SYSTEM",
                    null, AuditReason.SETTING_CHANGED,
                    Map.of("key", key, "oldValue", oldValue == null ? "null" : oldValue),
                    Map.of("key", key, "newValue", newValue == null ? "null" : newValue),
                    null);
        } catch (Exception ex) {
            log.warn("Project setting audit failed for {}/{} (continuing): {}", projectId, key, ex.getMessage());
        }
    }
}