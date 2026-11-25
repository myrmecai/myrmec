// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.seed;

import java.util.List;

/**
 * Seed DTO for an assistant, read from {@code seed-data/optional/assistant.json}.
 * Mirrors the {@code Assistant} identity fields plus the role bindings
 * (which reference agent profiles by alias).
 */
public record AssistantSeed(
        AssistantIdentity assistant,
        List<AgentRole> agentRoles) {

    /** Identity fields for the {@code assistants} row. */
    public record AssistantIdentity(
            String name,
            String description,
            String team) {
    }

    /** One {@code harness_agent_bindings} row, referencing a profile by alias. */
    public record AgentRole(
            String roleType,
            String agentAlias,
            String profileAlias,
            String promptAddendum) {
    }
}