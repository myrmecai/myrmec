// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

/**
 * Per-host model access mode (credential-envelope design &sect;5).
 *
 * <p>{@code DIRECT} &mdash; the host calls the provider endpoint directly
 * with the enveloped provider credential (accounting semantics: usage is
 * host self-reported, quota checked at dispatch).
 *
 * <p>{@code GATEWAY} &mdash; model calls route through the org model gateway
 * (enforcement semantics: per-call metering + audit at the choke point).
 * Not implementable until Phase 2; the engine fails dispatches for
 * GATEWAY-mode hosts loudly instead of silently degrading to Direct.
 *
 * <p>Settable only by {@code PLATFORM_ADMIN} (design &sect;5.1); not exposed
 * at project scope.
 */
public enum ModelAccessMode {
    DIRECT,
    GATEWAY
}