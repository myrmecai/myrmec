// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import lombok.Getter;

import java.util.List;

/**
 * Governance product features — the configurable knobs that each
 * governance profile (STRICT, STANDARD, FLEXIBLE) sets values for.
 *
 * <p>Features are either <b>single-value</b> (the profile picks one
 * value, e.g. {@code MANIFEST_RETENTION}) or <b>multi-value</b> (the
 * profile picks a subset of values, e.g. {@code INSTRUCTION_SOURCES}).
 *
 * <p>The compare matrix in the UI renders all features grouped by
 * {@link FeatureGroup}, sorted by {@link #sortOrder}.
 */
@Getter
public enum ProductFeature {
    INSTRUCTION_SOURCES(
            "Allowed instruction source types",
            FeatureGroup.AI_CONTEXT, 10,
            List.of("INLINE", "GIT"), true),
    INLINE_INSTRUCTIONS_SCOPE(
            "Where inline instructions are allowed",
            FeatureGroup.AI_CONTEXT, 20,
            List.of("NONE", "PROJECT_SERVICE", "ALL"), false),
    KNOWLEDGE_PROVIDERS(
            "Allowed knowledge provider types",
            FeatureGroup.AI_CONTEXT, 30,
            List.of("MANAGED", "EXTERNAL"), true),
    DATA_FEEDS(
            "Allowed data feed source types",
            FeatureGroup.AI_CONTEXT, 40,
            List.of("GIT", "WEB_CRAWL", "CONFLUENCE", "JIRA", "NOTION", "S3", "DB_SCHEMA"), true),
    MANIFEST_RETENTION(
            "How long to retain context manifests",
            FeatureGroup.AI_CONTEXT, 50,
            List.of("365_DAYS", "90_DAYS", "30_DAYS"), false),
    MANIFEST_FREQUENCY(
            "How often to record context manifests",
            FeatureGroup.AI_CONTEXT, 60,
            List.of("FULL", "SAMPLED"), false),
    CONTEXT_PINNING(
            "Pin context at execution start (no live updates)",
            FeatureGroup.AI_CONTEXT, 70,
            List.of("ON", "OFF"), false),
    ACTIVATION_RULES(
            "When activation rules are required",
            FeatureGroup.AI_CONTEXT, 80,
            List.of("REQUIRED_ALL", "REQUIRED_ORG_PROJECT", "OPTIONAL"), false),
    BUDGET_ENFORCEMENT(
            "Enforce token budget caps",
            FeatureGroup.BUDGET, 90,
            List.of("ON", "OFF"), false),
    BUDGET_OVERRIDE(
            "Allow per-service budget overrides",
            FeatureGroup.BUDGET, 100,
            List.of("NONE", "PER_SERVICE", "CONFIGURABLE"), false);

    private final String description;
    private final FeatureGroup group;
    private final int sortOrder;
    private final List<String> possibleValues;
    private final boolean multiValue;

    ProductFeature(String description, FeatureGroup group, int sortOrder,
                   List<String> possibleValues, boolean multiValue) {
        this.description = description;
        this.group = group;
        this.sortOrder = sortOrder;
        this.possibleValues = possibleValues;
        this.multiValue = multiValue;
    }
}