// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.governance.dto.FeatureGroupResponse;
import ai.myrmec.engine.governance.dto.GovernanceProfileResponse;
import ai.myrmec.engine.setting.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Governance Profile Service — read-only access to built-in profiles, policy
 * resolution, and org-level default profile selection.
 *
 * <p>After the governance-enforcement rewrite (G2/G3), built-in profiles are
 * hardcoded Java enums ({@link BuiltInGovernanceProfile}). The legacy
 * {@code governance_profiles} table, entity, repository, JSONB policies,
 * and CRUD operations have been removed (immutable built-ins — G2).
 *
 * <p>Key methods:
 * <ul>
 *   <li>{@link #getCurrentDefaultProfileCode} — reads the current org-level
 *       default profile code from system settings.</li>
 *   <li>{@link #setDefaultProfile} — updates the org-level default profile
 *       and records an audit event.</li>
 *   <li>{@link #findAllWithGroups} — returns all built-in profiles with
 *       structured feature groups for the compare matrix UI.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceProfileService {

    /** System setting key for the org-level governance profile. */
    public static final String GOVERNANCE_PROFILE_KEY = "governance_profile_code";

    /** Default profile code when the setting is absent. */
    public static final String DEFAULT_PROFILE_CODE = "STANDARD";

    private final GovernanceProfileFactory factory;
    private final GovernanceProfileProvider provider;
    private final SystemSettingService systemSettingService;
    private final AuditEventService auditEventService;

    /**
     * List all built-in profile definitions.
     */
    public List<GovernanceProfileDefinition> findAll() {
        return Arrays.stream(BuiltInGovernanceProfile.values())
                .map(p -> (GovernanceProfileDefinition) p)
                .toList();
    }

    /**
     * Find a profile definition by code.
     */
    public GovernanceProfileDefinition findByCode(String code) {
        return provider.resolve(code);
    }

    /**
     * Get the current org-level default profile code from system settings.
     *
     * @return the profile code (defaults to STANDARD if unset)
     */
    @Transactional(readOnly = true)
    public String getCurrentDefaultProfileCode() {
        return systemSettingService.getString(GOVERNANCE_PROFILE_KEY, DEFAULT_PROFILE_CODE);
    }

    /**
     * Set the org-level default governance profile.
     *
     * <p>Updates the {@code governance_profile_code} system setting and
     * records an audit event with the previous and new profile codes.</p>
     *
     * @param profileCode  the new profile code (must be a valid built-in profile)
     * @param actorUserId  the user making the change (for audit)
     */
    @Transactional
    public void setDefaultProfile(String profileCode, UUID actorUserId) {
        // Validate the profile exists
        provider.resolve(profileCode);
        String previousCode = getCurrentDefaultProfileCode();

        if (profileCode.equals(previousCode)) {
            log.info("Governance profile already set to {}; no change", profileCode);
            return;
        }

        // Update the system setting (this also records a SystemSetting audit event)
        systemSettingService.update(GOVERNANCE_PROFILE_KEY, profileCode, actorUserId);

        // Record a governance-specific audit event
        auditEventService.recordEvent(
                ResourceType.GOVERNANCE_PROFILE, null, AuditAction.GOVERNANCE_PROFILE_CHANGED,
                "ORGANIZATION", null,
                actorUserId, actorUserId != null ? "USER" : "SYSTEM",
                null, null,
                Map.of("previousProfile", previousCode),
                Map.of("newProfile", profileCode),
                Map.of("profileCode", profileCode, "previousCode", previousCode));

        log.info("Governance profile changed: {} -> {} (actor: {})", previousCode, profileCode, actorUserId);
    }

    /**
     * Get all profiles with structured feature groups and current-default flag,
     * for the compare matrix UI.
     */
    @Transactional(readOnly = true)
    public List<GovernanceProfileResponse> findAllWithGroups() {
        String currentDefault = getCurrentDefaultProfileCode();
        return findAll().stream()
                .map(d -> {
                    List<FeatureGroupResponse> groups = factory.buildGroups(d);
                    return GovernanceProfileResponse.from(d, d.code().equals(currentDefault), groups);
                })
                .toList();
    }

    /**
     * Get a single profile with structured feature groups and current-default flag.
     */
    @Transactional(readOnly = true)
    public GovernanceProfileResponse findByCodeWithGroups(String code) {
        String currentDefault = getCurrentDefaultProfileCode();
        GovernanceProfileDefinition def = provider.resolve(code);
        List<FeatureGroupResponse> groups = factory.buildGroups(def);
        return GovernanceProfileResponse.from(def, code.equals(currentDefault), groups);
    }

    /**
     * Get the current default profile with structured feature groups.
     */
    @Transactional(readOnly = true)
    public GovernanceProfileResponse getCurrentDefault() {
        String code = getCurrentDefaultProfileCode();
        return findByCodeWithGroups(code);
    }
}