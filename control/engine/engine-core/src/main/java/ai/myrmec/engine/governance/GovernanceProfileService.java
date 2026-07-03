// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.governance.dto.FeatureGroupResponse;
import ai.myrmec.engine.governance.dto.GovernanceProfileResponse;
import ai.myrmec.engine.setting.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Governance Profile Service — CRUD for governance profiles, policy retrieval,
 * and org-level default profile selection.
 *
 * <p>The key methods are:
 * <ul>
 *   <li>{@link #getEffectivePolicies} — resolves the effective governance
 *       policies for a project by reading the {@code governance_profile_code}
 *       system setting.</li>
 *   <li>{@link #getCurrentDefaultProfileCode} — reads the current org-level
 *       default profile code from system settings.</li>
 *   <li>{@link #setDefaultProfile} — updates the org-level default profile
 *       and records an audit event.</li>
 *   <li>{@link #findAllWithGroups} — returns all profiles with structured
 *       feature groups for the compare matrix UI.</li>
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

    private final GovernanceProfileRepository repository;
    private final GovernanceProfileFactory factory;
    private final SystemSettingService systemSettingService;
    private final AuditEventService auditEventService;

    @Transactional(readOnly = true)
    public List<GovernanceProfile> findAll() {
        return repository.findAllByOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public GovernanceProfile findByCode(String code) {
        return repository.findById(code)
                .orElseThrow(() -> ResourceNotFoundException.of("GovernanceProfile", code));
    }

    /**
     * Resolve the effective governance policies for a project.
     *
     * @param profileCode the governance profile code (e.g., STRICT, STANDARD, FLEXIBLE)
     * @return the policy collection as a map
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getEffectivePolicies(String profileCode) {
        GovernanceProfile profile = findByCode(profileCode);
        return profile.getPolicies();
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
     * @throws ResourceNotFoundException if the profile code does not exist
     */
    @Transactional
    public void setDefaultProfile(String profileCode, UUID actorUserId) {
        // Validate the profile exists
        GovernanceProfile profile = findByCode(profileCode);
        String previousCode = getCurrentDefaultProfileCode();

        if (profileCode.equals(previousCode)) {
            log.info("Governance profile already set to {}; no change", profileCode);
            return;
        }

        // Update the system setting (this also records a SystemSetting audit event)
        systemSettingService.update(GOVERNANCE_PROFILE_KEY, profileCode, actorUserId);

        // Record a governance-specific audit event
        auditEventService.recordEvent(
                "GovernanceProfile", null, "GOVERNANCE_PROFILE_CHANGED",
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
     *
     * @return list of GovernanceProfileResponse with groups populated
     */
    @Transactional(readOnly = true)
    public List<GovernanceProfileResponse> findAllWithGroups() {
        String currentDefault = getCurrentDefaultProfileCode();
        return findAll().stream()
                .map(p -> {
                    List<FeatureGroupResponse> groups = factory.buildGroups(p);
                    return GovernanceProfileResponse.from(p, p.getCode().equals(currentDefault), groups);
                })
                .toList();
    }

    /**
     * Get a single profile with structured feature groups and current-default flag.
     *
     * @param code the profile code
     * @return GovernanceProfileResponse with groups populated
     */
    @Transactional(readOnly = true)
    public GovernanceProfileResponse findByCodeWithGroups(String code) {
        String currentDefault = getCurrentDefaultProfileCode();
        GovernanceProfile profile = findByCode(code);
        List<FeatureGroupResponse> groups = factory.buildGroups(profile);
        return GovernanceProfileResponse.from(profile, code.equals(currentDefault), groups);
    }

    /**
     * Get the current default profile with structured feature groups.
     *
     * @return GovernanceProfileResponse for the current default profile
     */
    @Transactional(readOnly = true)
    public GovernanceProfileResponse getCurrentDefault() {
        String code = getCurrentDefaultProfileCode();
        return findByCodeWithGroups(code);
    }

    @Transactional
    public GovernanceProfile create(GovernanceProfile profile) {
        log.info("Creating governance profile: {}", profile.getCode());
        return repository.save(profile);
    }

    @Transactional
    public GovernanceProfile update(String code, GovernanceProfile updated) {
        GovernanceProfile existing = findByCode(code);
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setPolicies(updated.getPolicies());
        log.info("Updating governance profile: {}", code);
        return repository.save(existing);
    }

    @Transactional
    public void delete(String code) {
        GovernanceProfile profile = findByCode(code);
        if (Boolean.TRUE.equals(profile.getIsSystem())) {
            throw new IllegalStateException("Cannot delete system governance profile: " + code);
        }
        log.info("Deleting governance profile: {}", code);
        repository.delete(profile);
    }
}