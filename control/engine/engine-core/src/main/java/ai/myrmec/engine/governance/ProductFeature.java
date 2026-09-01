// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import lombok.Getter;

import java.util.List;
import java.util.Map;

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
 *
 * <p><b>Invariant:</b> {@link #possibleValues} is ordered
 * <b>strictest → loosest</b>. Index 0 is the most restrictive value.
 * {@code FeatureStrictness} depends on this ordering — do not reorder
 * the values without updating the strictness comparisons.
 *
 * <p>Each feature carries an {@link EnforcementKind} that drives whether
 * the {@code GovernancePolicyEnforcer} guards an action point
 * ({@code BLOCKING}) or a runtime consumer reads it
 * ({@code BEHAVIORAL}).
 *
 * <p>A few consumers persist or compare <b>runtime names</b> that differ
 * from the enum value (see {@code CONTEXT-AND-GOVERNANCE.md} §4.2).
 * The {@link #runtimeName(String)} method maps an enum value to its
 * runtime alias; when there is no alias the value passes through
 * unchanged.
 */
@Getter
public enum ProductFeature {
    INSTRUCTION_SOURCES(
            "Allowed instruction source types",
            FeatureGroup.AI_CONTEXT, 10,
            List.of("INLINE", "GIT"), true,
            EnforcementKind.BLOCKING),
    INLINE_INSTRUCTIONS_SCOPE(
            "Where inline instructions are allowed",
            FeatureGroup.AI_CONTEXT, 20,
            List.of("NONE", "PROJECT_SERVICE", "ALL"), false,
            EnforcementKind.BLOCKING),
    KNOWLEDGE_PROVIDERS(
            "Allowed knowledge provider types",
            FeatureGroup.AI_CONTEXT, 30,
            List.of("MANAGED", "EXTERNAL"), true,
            EnforcementKind.BLOCKING),
    DATA_FEEDS(
            "Allowed data feed source types",
            FeatureGroup.AI_CONTEXT, 40,
            List.of("GIT", "WEB_CRAWL", "CONFLUENCE", "JIRA", "NOTION", "S3", "DB_SCHEMA"), true,
            EnforcementKind.BLOCKING),
    MANIFEST_RETENTION(
            "How long to retain context manifests",
            FeatureGroup.AI_CONTEXT, 50,
            List.of("365_DAYS", "90_DAYS", "30_DAYS"), false,
            EnforcementKind.BEHAVIORAL),
    MANIFEST_FREQUENCY(
            "How often to record context manifests",
            FeatureGroup.AI_CONTEXT, 60,
            List.of("FULL", "SAMPLED"), false,
            EnforcementKind.BEHAVIORAL,
            Map.of("FULL", "contextManifestOnEveryExecution=true",
                    "SAMPLED", "contextManifestOnEveryExecution=false")),
    CONTEXT_PINNING(
            "Pin context at execution start (no live updates)",
            FeatureGroup.AI_CONTEXT, 70,
            List.of("ON", "OFF"), false,
            EnforcementKind.BEHAVIORAL,
            Map.of("ON", "PINNED_AT_START",
                    "OFF", "IMMEDIATE_EFFECT")),
    ACTIVATION_RULES(
            "When activation rules are required",
            FeatureGroup.AI_CONTEXT, 80,
            List.of("REQUIRED_ALL", "REQUIRED_ORG_PROJECT", "OPTIONAL"), false,
            EnforcementKind.BEHAVIORAL),
    BUDGET_ENFORCEMENT(
            "Enforce token budget caps",
            FeatureGroup.BUDGET, 90,
            List.of("ON", "OFF"), false,
            EnforcementKind.BEHAVIORAL),
    BUDGET_OVERRIDE(
            "Allow per-service budget overrides",
            FeatureGroup.BUDGET, 100,
            List.of("NONE", "PER_SERVICE", "CONFIGURABLE"), false,
            EnforcementKind.BLOCKING,
            Map.of("NONE", "HARD_CAP_NO_OVERRIDE",
                    "PER_SERVICE", "HARD_CAP_PER_SERVICE")),
    /**
     * Tamper-evident audit log (hash-chain).
     *
     * <p>STRICT profile forces this ON (chaining always active, every project).
     * STANDARD/FLEXIBLE allow each project to opt in via the
     * {@code audit_integrity_enabled} project setting.
     */
    AUDIT_INTEGRITY(
            "Tamper-evident audit log (hash-chained events)",
            FeatureGroup.BUDGET, 110,
            List.of("ON", "OFF"), false,
            EnforcementKind.BEHAVIORAL,
            Map.of("ON", "auditHashChain=on",
                    "OFF", "auditHashChain=off"));

    private final String description;
    private final FeatureGroup group;
    private final int sortOrder;
    private final List<String> possibleValues;
    private final boolean multiValue;
    private final EnforcementKind kind;
    /** Runtime-name aliases (§4.2); empty map when value === runtimeName. */
    private final Map<String, String> runtimeNames;

    ProductFeature(String description, FeatureGroup group, int sortOrder,
                   List<String> possibleValues, boolean multiValue,
                   EnforcementKind kind) {
        this(description, group, sortOrder, possibleValues, multiValue, kind, Map.of());
    }

    ProductFeature(String description, FeatureGroup group, int sortOrder,
                   List<String> possibleValues, boolean multiValue,
                   EnforcementKind kind, Map<String, String> runtimeNames) {
        this.description = description;
        this.group = group;
        this.sortOrder = sortOrder;
        this.possibleValues = possibleValues;
        this.multiValue = multiValue;
        this.kind = kind;
        this.runtimeNames = runtimeNames;
    }

    /**
     * Map an enum value to its runtime-name alias (§4.2). When there is
     * no alias the value passes through unchanged.
     */
    public String runtimeName(String value) {
        return runtimeNames.getOrDefault(value, value);
    }

    /**
     * Resolve a {@code MANIFEST_RETENTION} value string to its retention
     * window in days.
     *
     * @param value one of {@code 365_DAYS}, {@code 90_DAYS}, {@code 30_DAYS}
     * @return the number of days, or {@code -1} if the value is unrecognised
     *         (caller should skip pruning for that profile)
     */
    public static int manifestRetentionDays(String value) {
        return switch (value) {
            case "365_DAYS" -> 365;
            case "90_DAYS" -> 90;
            case "30_DAYS" -> 30;
            default -> -1;
        };
    }
}