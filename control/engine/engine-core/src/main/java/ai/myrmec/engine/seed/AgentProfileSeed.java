// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.seed;

import java.util.List;
import java.util.Map;

/**
 * Seed DTO for an agent profile, read from
 * {@code seed-data/optional/agent-profiles.json}. Mirrors the Zone-1
 * identity fields of {@code AgentProfile} plus the Zone-2 behaviour contract
 * published as version 1.
 *
 * <p>{@code alias} is the stable symbolic key used by the assistant-binding
 * stage to reference this profile's published version.</p>
 */
public record AgentProfileSeed(
        String alias,
        String name,
        String description,
        boolean addendumAllowed,
        String systemPrompt,
        String defaultModel,
        String interactionMode,
        List<String> capabilities,
        List<String> toolCodes,
        Map<String, Object> commandTemplates,
        Map<String, Object> approvalPolicy,
        Integer approvalRequestTtlSeconds) {
}
