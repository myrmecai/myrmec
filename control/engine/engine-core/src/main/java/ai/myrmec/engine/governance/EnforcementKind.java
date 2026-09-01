// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

/**
 * Whether a {@link ProductFeature} is enforced by throwing at an action point
 * or read by a runtime consumer.
 *
 * <p>{@code BLOCKING} features are guarded by {@code GovernancePolicyEnforcer.assertAllowed}
 * at a service/controller entry point — a violation throws
 * {@code GovernanceViolationException} (HTTP 403 {@code GOVERNANCE_VIOLATION}).
 *
 * <p>{@code BEHAVIORAL} features are read by a runtime consumer (e.g.
 * {@code ContextBuilder} for {@code CONTEXT_PINNING}, the manifest writer for
 * {@code MANIFEST_FREQUENCY}) via {@code EffectivePolicy.single(feature)} or
 * {@code ProductFeature.runtimeName(value)}. They never throw.
 */
public enum EnforcementKind {
    /** Enforced by the enforcer at an action point — violation → 403. */
    BLOCKING,
    /** Read by a runtime consumer — no user-facing block. */
    BEHAVIORAL
}