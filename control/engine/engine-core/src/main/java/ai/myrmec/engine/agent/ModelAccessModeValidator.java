// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine.governance.EffectivePolicy;
import ai.myrmec.engine.governance.GovernancePolicyResolver;
import ai.myrmec.engine.governance.GovernanceViolationException;
import ai.myrmec.engine.governance.ProductFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Model access mode validation matrix (credential-envelope design
 * &sect;5.2) plus the governance mandate (&sect;5.3).
 *
 * <p>Enforced at host create/edit and at mode change so a host never
 * discovers at {@code session.open} time that its mode is unbacked:
 *
 * <table>
 *   <caption>&sect;5.2 matrix</caption>
 *   <tr><th>Gateway</th><th>Host type</th><th>Mode</th><th>Rule</th></tr>
 *   <tr><td>off</td><td>any</td><td>DIRECT</td><td>allowed (dev-phase default)</td></tr>
 *   <tr><td>off</td><td>any</td><td>GATEWAY</td><td>rejected &mdash; mode unbacked</td></tr>
 *   <tr><td>on</td><td>MANAGED</td><td>DIRECT</td><td>allowed (trusted-infrastructure accounting)</td></tr>
 *   <tr><td>on</td><td>MANAGED</td><td>GATEWAY</td><td>allowed</td></tr>
 *   <tr><td>on</td><td>LOCAL</td><td>GATEWAY</td><td>allowed (recommended)</td></tr>
 *   <tr><td>on</td><td>LOCAL</td><td>DIRECT</td><td>rejected when the governance profile
 *       mandates gateway (LOCAL_HOST_MODEL_GATEWAY = ON); otherwise allowed</td></tr>
 * </table>
 *
 * <p>The mandate is read from the <b>org-default</b> governance profile
 * (design &sect;5.3: V1 check is org scope; per-project scope deferred).
 * Violations throw {@link GovernanceViolationException} (HTTP 403
 * {@code GOVERNANCE_VIOLATION}); plain matrix violations throw
 * {@link BadRequestException}, matching the host service's existing
 * validation-error convention.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ModelAccessModeValidator {

    private final ModelGatewaySettings gatewaySettings;
    private final GovernancePolicyResolver governancePolicyResolver;

    /**
     * Validate a host's model access mode against the current gateway
     * configuration and the org governance mandate.
     *
     * @param hostType the durable host type (MANAGED / LOCAL / DEDICATED)
     * @param mode     the requested model access mode
     * @throws BadRequestException            when the matrix rejects the combination
     * @throws GovernanceViolationException   when the org profile mandates gateway
     *                                        for LOCAL hosts and the combination is LOCAL+DIRECT
     */
    public void validate(AgentHostType hostType, ModelAccessMode mode) {
        if (mode == ModelAccessMode.GATEWAY && !gatewaySettings.gatewayEnabled()) {
            // A host must never discover at session.open time that its mode
            // is unbacked (design §5.2) — fail at configuration time.
            throw BadRequestException.forField(
                    "modelAccessMode", "INVALID_VALUE",
                    "Model access mode GATEWAY requires the model gateway to be enabled "
                            + "(system setting " + ModelGatewaySettings.ENABLED_KEY + ").");
        }
        if (hostType == AgentHostType.LOCAL && mode == ModelAccessMode.DIRECT) {
            EffectivePolicy policy = governancePolicyResolver.resolveOrgDefault();
            if ("ON".equals(policy.single(ProductFeature.LOCAL_HOST_MODEL_GATEWAY))) {
                throw new GovernanceViolationException(
                        ProductFeature.LOCAL_HOST_MODEL_GATEWAY,
                        "OFF",
                        policy.values(ProductFeature.LOCAL_HOST_MODEL_GATEWAY),
                        policy.code());
            }
        }
        // Remaining combinations (gateway on/off × DIRECT; gateway on ×
        // MANAGED/LOCAL GATEWAY) are allowed per the §5.2 matrix.
        log.debug("Model access mode validated: hostType={}, mode={}", hostType, mode);
    }
}