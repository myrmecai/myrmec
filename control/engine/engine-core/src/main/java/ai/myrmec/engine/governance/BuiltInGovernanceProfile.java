// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import java.util.Map;
import java.util.Set;
import static java.util.Map.entry;

/**
 * The three immutable built-in governance profiles.
 *
 * <p>Values are straight from {@code CONTEXT-AND-GOVERNANCE.md} §4.1 (the
 * canonical matrix). Each profile maps every {@link ProductFeature} to a
 * {@link Set} of values — single-value features hold a 1-element set.
 *
 * <p>These are hardcoded and immutable (G2): no CRUD, no DB seed, no JSONB.
 * The {@link GovernanceProfileProvider} resolves a code to a
 * {@link GovernanceProfileDefinition} by looking here first.
 *
 * <p><b>Completeness invariant:</b> every {@link ProductFeature} has a value
 * in every built-in profile. The static initializer below asserts this at
 * class-load time so adding a feature to the enum forces updating all
 * profiles.
 */
public enum BuiltInGovernanceProfile implements GovernanceProfileDefinition {

    STRICT(
            "STRICT", "Strict",
            Map.ofEntries(
                    entry(ProductFeature.INSTRUCTION_SOURCES, Set.of("GIT")),
                    entry(ProductFeature.INLINE_INSTRUCTIONS_SCOPE, Set.of("NONE")),
                    entry(ProductFeature.KNOWLEDGE_PROVIDERS, Set.of("MANAGED")),
                    entry(ProductFeature.DATA_FEEDS, Set.of("GIT")),
                    entry(ProductFeature.MANIFEST_RETENTION, Set.of("365_DAYS")),
                    entry(ProductFeature.MANIFEST_FREQUENCY, Set.of("FULL")),
                    entry(ProductFeature.CONTEXT_PINNING, Set.of("ON")),
                    entry(ProductFeature.ACTIVATION_RULES, Set.of("REQUIRED_ALL")),
                    entry(ProductFeature.BUDGET_ENFORCEMENT, Set.of("ON")),
                    entry(ProductFeature.BUDGET_OVERRIDE, Set.of("NONE")),
                    entry(ProductFeature.AUDIT_INTEGRITY, Set.of("ON")))),

    STANDARD(
            "STANDARD", "Standard",
            Map.ofEntries(
                    entry(ProductFeature.INSTRUCTION_SOURCES, Set.of("INLINE", "GIT")),
                    entry(ProductFeature.INLINE_INSTRUCTIONS_SCOPE, Set.of("PROJECT_SERVICE")),
                    entry(ProductFeature.KNOWLEDGE_PROVIDERS, Set.of("MANAGED", "EXTERNAL")),
                    entry(ProductFeature.DATA_FEEDS, Set.of("GIT", "CONFLUENCE", "JIRA", "NOTION")),
                    entry(ProductFeature.MANIFEST_RETENTION, Set.of("90_DAYS")),
                    entry(ProductFeature.MANIFEST_FREQUENCY, Set.of("FULL")),
                    entry(ProductFeature.CONTEXT_PINNING, Set.of("ON")),
                    entry(ProductFeature.ACTIVATION_RULES, Set.of("REQUIRED_ORG_PROJECT")),
                    entry(ProductFeature.BUDGET_ENFORCEMENT, Set.of("ON")),
                    entry(ProductFeature.BUDGET_OVERRIDE, Set.of("PER_SERVICE")),
                    entry(ProductFeature.AUDIT_INTEGRITY, Set.of("OFF")))),

    FLEXIBLE(
            "FLEXIBLE", "Flexible",
            Map.ofEntries(
                    entry(ProductFeature.INSTRUCTION_SOURCES, Set.of("INLINE", "GIT")),
                    entry(ProductFeature.INLINE_INSTRUCTIONS_SCOPE, Set.of("ALL")),
                    entry(ProductFeature.KNOWLEDGE_PROVIDERS, Set.of("MANAGED", "EXTERNAL")),
                    entry(ProductFeature.DATA_FEEDS, Set.of("GIT", "WEB_CRAWL", "CONFLUENCE", "JIRA", "NOTION", "S3", "DB_SCHEMA")),
                    entry(ProductFeature.MANIFEST_RETENTION, Set.of("30_DAYS")),
                    entry(ProductFeature.MANIFEST_FREQUENCY, Set.of("SAMPLED")),
                    entry(ProductFeature.CONTEXT_PINNING, Set.of("OFF")),
                    entry(ProductFeature.ACTIVATION_RULES, Set.of("OPTIONAL")),
                    entry(ProductFeature.BUDGET_ENFORCEMENT, Set.of("ON")),
                    entry(ProductFeature.BUDGET_OVERRIDE, Set.of("CONFIGURABLE")),
                    entry(ProductFeature.AUDIT_INTEGRITY, Set.of("OFF"))));

    private final String code;
    private final String displayName;
    private final Map<ProductFeature, Set<String>> values;

    BuiltInGovernanceProfile(String code, String displayName,
                             Map<ProductFeature, Set<String>> values) {
        this.code = code;
        this.displayName = displayName;
        this.values = Map.copyOf(values);
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public boolean builtIn() {
        return true;
    }

    @Override
    public Map<ProductFeature, Set<String>> featureValues() {
        return values;
    }

    /**
     * Resolve a code to a built-in profile. Returns {@code null} if the
     * code is not a built-in (the caller —
     * {@link GovernanceProfileProvider} — handles custom lookups).
     */
    public static BuiltInGovernanceProfile byCode(String code) {
        for (var p : BuiltInGovernanceProfile.values()) {
            if (p.code.equals(code)) {
                return p;
            }
        }
        return null;
    }


    static {
        for (var profile : values()) {
            for (var feature : ProductFeature.values()) {
                if (!profile.values.containsKey(feature)) {
                    throw new IllegalStateException(
                            "BuiltInGovernanceProfile " + profile.code
                                    + " is missing a value for " + feature.name());
                }
            }
        }
    }
}