// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent.dto;

/**
 * Capability information resolved for an agent profile version,
 * including the catalog display name and description.
 */
public record AgentProfileCapabilityInfo(
        String capabilityKey,
        String displayName,
        String description) {
}
