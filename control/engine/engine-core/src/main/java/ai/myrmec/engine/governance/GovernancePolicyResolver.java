// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine.setting.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves the effective governance policy for a given scope.
 *
 * <p>V1 behaviour:
 * <ol>
 *   <li>Read the org default code from system setting
 *       {@code governance_profile_code} (default {@code STANDARD}).</li>
 *   <li>If the scope has a project-level selection (future), it must be
 *       at-least-as-strict-as the org default ({@link FeatureStrictness}) —
 *       else reject at <i>write</i> time. At <i>read</i> time, resolve to
 *       the project selection if present, else the org default.</li>
 *   <li>Return an {@link EffectivePolicy} built from the resolved
 *       {@link GovernanceProfileDefinition}.</li>
 * </ol>
 *
 * <p>For V1, project-level selection storage does not exist yet — the
 * resolver returns the org default and the ratchet check is exercised by
 * the (future) project-selection write path. The scope parameter is kept
 * so no signature change is needed when project selection lands.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernancePolicyResolver {

    /** System setting key for the org-level governance profile. */
    public static final String GOVERNANCE_PROFILE_KEY = "governance_profile_code";

    /** Default profile code when the setting is absent. */
    public static final String DEFAULT_PROFILE_CODE = "STANDARD";

    private final SystemSettingService systemSettingService;
    private final GovernanceProfileProvider provider;

    /**
     * Resolve the effective policy for the given scope.
     *
     * @param scope the scope (null projectId ⇒ org scope)
     * @return the effective policy
     */
    public EffectivePolicy resolve(GovernanceScope scope) {
        String code = systemSettingService.getString(GOVERNANCE_PROFILE_KEY, DEFAULT_PROFILE_CODE);
        GovernanceProfileDefinition definition = provider.resolve(code);
        log.debug("Resolved governance profile {} for scope {}", code, scope);
        return new EffectivePolicy(definition);
    }

    /**
     * Resolve the effective policy for org scope (convenience).
     */
    public EffectivePolicy resolveOrgDefault() {
        return resolve(GovernanceScope.orgScope());
    }

    /**
     * Validate that a candidate project-level selection is at-least-as-strict-as
     * the org default (ratchet check). To be called on the write path when
     * project selection lands.
     *
     * @param projectProfileCode the candidate project profile code
     * @return true if the candidate is at-least-as-strict-as the org default
     */
    public boolean isRatchetValid(String projectProfileCode) {
        String orgCode = systemSettingService.getString(GOVERNANCE_PROFILE_KEY, DEFAULT_PROFILE_CODE);
        GovernanceProfileDefinition projectDef = provider.resolve(projectProfileCode);
        GovernanceProfileDefinition orgDef = provider.resolve(orgCode);
        return FeatureStrictness.atLeastAsStrict(projectDef, orgDef);
    }
}