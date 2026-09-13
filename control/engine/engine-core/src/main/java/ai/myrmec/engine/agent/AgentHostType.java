// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

/**
 * Durable host type (protocol §2.3 / design 2026-09-11 §2.3) — replaces the
 * {@code is_local} boolean. {@code MANAGED} = cluster/headless host (no owner,
 * at most one live instance); {@code LOCAL} = a user's IDE supervisor (one
 * host per owner); {@code DEDICATED} = reserved for seat-pinned workstations,
 * not built yet.
 */
public enum AgentHostType {
    MANAGED,
    LOCAL,
    DEDICATED
}
