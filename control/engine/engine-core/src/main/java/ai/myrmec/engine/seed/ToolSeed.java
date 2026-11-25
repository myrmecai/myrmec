// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.seed;

import java.util.Map;

/**
 * Seed DTO for a tool, read from {@code seed-data/optional/tools.json}.
 * Mirrors the {@code Tool} entity fields relevant to seeding (the SDK-owned
 * tool names and their HITL risk classification). {@code isSystem} and
 * {@code status} are defaulted by the seeder.
 */
public record ToolSeed(
        String code,
        String name,
        String description,
        String toolType,
        Map<String, Object> configSchema,
        String docsUrl,
        String riskClass) {
}
