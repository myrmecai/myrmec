// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine.governance.dto.FeatureGroupResponse;
import ai.myrmec.engine.governance.dto.FeatureValueResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Translates raw governance profile policies (JSONB from DB) into
 * structured {@link FeatureGroupResponse} rows for the compare matrix UI.
 *
 * <p>This is a pure mapping layer — it reads the existing {@code policies}
 * JSONB from the {@link GovernanceProfile} entity and maps each policy
 * key to the corresponding {@link ProductFeature}. The profiles themselves
 * remain stored in the database (seeded by Liquibase); this factory only
 * shapes the response DTO.</p>
 */
@Slf4j
@Component
public class GovernanceProfileFactory {

    /**
     * Build the feature-group structure for a single profile.
     *
     * @param profile the governance profile entity
     * @return list of feature groups, each containing its feature rows
     */
    public List<FeatureGroupResponse> buildGroups(GovernanceProfile profile) {
        Map<String, Object> policies = profile.getPolicies();
        Map<FeatureGroup, List<FeatureValueResponse>> grouped = new EnumMap<>(FeatureGroup.class);

        for (ProductFeature feature : ProductFeature.values()) {
            List<String> currentValues = extractValues(policies, feature);
            FeatureValueResponse fvr = new FeatureValueResponse(
                    feature.name(),
                    feature.getDescription(),
                    feature.getSortOrder(),
                    feature.getPossibleValues(),
                    currentValues);
            grouped.computeIfAbsent(feature.getGroup(), k -> new ArrayList<>()).add(fvr);
        }

        return Arrays.stream(FeatureGroup.values())
                .map(g -> new FeatureGroupResponse(
                        g.name(),
                        g.getDescription(),
                        g.getSortOrder(),
                        grouped.getOrDefault(g, List.of()).stream()
                                .sorted(Comparator.comparingInt(FeatureValueResponse::sortOrder))
                                .toList()))
                .sorted(Comparator.comparingInt(FeatureGroupResponse::sortOrder))
                .toList();
    }

    /**
     * Extract the current values for a feature from the raw policies map.
     * Maps the legacy JSONB policy keys to the new ProductFeature enum.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractValues(Map<String, Object> policies, ProductFeature feature) {
        if (policies == null) {
            return List.of();
        }
        return switch (feature) {
            case INSTRUCTION_SOURCES -> toStringList(policies.get("instructionSourceTypes"));
            case INLINE_INSTRUCTIONS_SCOPE -> toScopeValue(policies.get("inlineInstructions"));
            case KNOWLEDGE_PROVIDERS -> toProviderValues(policies.get("knowledgeProviderTypes"));
            case DATA_FEEDS -> toDataFeedValues(policies.get("knowledgeDataSourceTypes"));
            case MANIFEST_RETENTION -> toRetentionValue(policies.get("contextManifestRetentionDays"));
            case MANIFEST_FREQUENCY -> toFrequencyValue(policies.get("contextManifestOnEveryExecution"));
            case CONTEXT_PINNING -> toPinningValue(policies.get("contextPinning"));
            case ACTIVATION_RULES -> toStringList(policies.get("activationRules"));
            case BUDGET_ENFORCEMENT -> toBudgetEnforcementValue(policies.get("budgetEnforcement"));
            case BUDGET_OVERRIDE -> toBudgetOverrideValue(policies.get("budgetEnforcement"));
        };
    }

    private List<String> toStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (raw instanceof String s) {
            return List.of(s);
        }
        return List.of();
    }

    /** Maps legacy inlineInstructions values to the new scope enum. */
    private List<String> toScopeValue(Object raw) {
        if (raw == null) return List.of("NONE");
        String s = String.valueOf(raw);
        return switch (s) {
            case "NOT_ALLOWED" -> List.of("NONE");
            case "PROJECT_SERVICE" -> List.of("PROJECT_SERVICE");
            case "ALL_SCOPES", "ALL" -> List.of("ALL");
            default -> List.of(s);
        };
    }

    /** Maps legacy provider types to the new MANAGED/EXTERNAL values. */
    private List<String> toProviderValues(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String s = String.valueOf(item);
                if (s.startsWith("EXTERNAL")) result.add("EXTERNAL");
                else if (s.equals("MANAGED")) result.add("MANAGED");
                else if (s.equals("ANY")) { result.add("MANAGED"); result.add("EXTERNAL"); }
                else result.add(s);
            }
            return result;
        }
        if ("ANY".equals(String.valueOf(raw))) return List.of("MANAGED", "EXTERNAL");
        return List.of(String.valueOf(raw));
    }

    /** Maps legacy data source types to the new DATA_FEEDS values. */
    private List<String> toDataFeedValues(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String s = String.valueOf(item);
                if (s.equals("ANY")) {
                    return List.of("GIT", "WEB_CRAWL", "CONFLUENCE", "JIRA", "NOTION", "S3", "DB_SCHEMA");
                }
                if (s.equals("EXTERNAL_HTTP")) continue; // skip non-feed types
                result.add(s);
            }
            return result;
        }
        String s = String.valueOf(raw);
        if ("ANY".equals(s)) return List.of("GIT", "WEB_CRAWL", "CONFLUENCE", "JIRA", "NOTION", "S3", "DB_SCHEMA");
        return List.of(s);
    }

    /** Maps legacy retention days (int) to the new retention enum values. */
    private List<String> toRetentionValue(Object raw) {
        if (raw == null) return List.of("90_DAYS");
        int days;
        if (raw instanceof Number n) days = n.intValue();
        else {
            try { days = Integer.parseInt(String.valueOf(raw)); }
            catch (NumberFormatException e) { return List.of("90_DAYS"); }
        }
        return switch (days) {
            case 365 -> List.of("365_DAYS");
            case 90 -> List.of("90_DAYS");
            case 30 -> List.of("30_DAYS");
            default -> List.of("90_DAYS");
        };
    }

    /** Maps legacy contextManifestOnEveryExecution (boolean) to FULL/SAMPLED. */
    private List<String> toFrequencyValue(Object raw) {
        if (raw == null) return List.of("FULL");
        if (raw instanceof Boolean b) return b ? List.of("FULL") : List.of("SAMPLED");
        String s = String.valueOf(raw);
        if ("true".equalsIgnoreCase(s)) return List.of("FULL");
        if ("false".equalsIgnoreCase(s)) return List.of("SAMPLED");
        return List.of(s);
    }

    /** Maps legacy contextPinning values to ON/OFF. */
    private List<String> toPinningValue(Object raw) {
        if (raw == null) return List.of("ON");
        String s = String.valueOf(raw);
        return switch (s) {
            case "PINNED_AT_START" -> List.of("ON");
            case "IMMEDIATE_EFFECT" -> List.of("OFF");
            default -> List.of(s);
        };
    }

    /** Maps legacy budgetEnforcement to ON/OFF. */
    private List<String> toBudgetEnforcementValue(Object raw) {
        if (raw == null) return List.of("ON");
        String s = String.valueOf(raw);
        if (s.startsWith("HARD_CAP")) return List.of("ON");
        if (s.equals("CONFIGURABLE")) return List.of("ON");
        if (s.equals("OFF")) return List.of("OFF");
        return List.of("ON");
    }

    /** Maps legacy budgetEnforcement to the override level. */
    private List<String> toBudgetOverrideValue(Object raw) {
        if (raw == null) return List.of("NONE");
        String s = String.valueOf(raw);
        if (s.equals("HARD_CAP_NO_OVERRIDE")) return List.of("NONE");
        if (s.equals("HARD_CAP_PER_SERVICE")) return List.of("PER_SERVICE");
        if (s.equals("CONFIGURABLE")) return List.of("CONFIGURABLE");
        return List.of("NONE");
    }
}