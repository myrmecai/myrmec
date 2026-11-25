// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.governance.dto.GovernanceProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for GovernanceProfileService — verifies built-in
 * profiles are resolvable, the compare matrix renders, and the
 * set-default flow works with audit events.
 *
 * <p>Rewritten (T6.5) to assert on {@link EffectivePolicy} and
 * {@link BuiltInGovernanceProfile} instead of raw JSONB maps.
 */
class GovernanceProfileServiceTest extends IntegrationTestBase {

    @Autowired
    private GovernanceProfileService service;

    @Test
    void findAllReturnsThreeBuiltIns() {
        var profiles = service.findAll();
        assertThat(profiles).hasSize(3);
        assertThat(profiles).extracting(GovernanceProfileDefinition::code)
                .containsExactlyInAnyOrder("STRICT", "STANDARD", "FLEXIBLE");
    }

    @Test
    void findByCodeResolvesBuiltIn() {
        GovernanceProfileDefinition def = service.findByCode("STANDARD");
        assertThat(def.code()).isEqualTo("STANDARD");
        assertThat(def.displayName()).isEqualTo("Standard");
        assertThat(def.builtIn()).isTrue();
    }

    @Test
    void strictProfileHasCorrectValues() {
        GovernanceProfileDefinition strict = service.findByCode("STRICT");
        assertThat(strict.featureValues().get(ProductFeature.INSTRUCTION_SOURCES)).containsExactly("GIT");
        assertThat(strict.featureValues().get(ProductFeature.INLINE_INSTRUCTIONS_SCOPE)).containsExactly("NONE");
        assertThat(strict.featureValues().get(ProductFeature.BUDGET_OVERRIDE)).containsExactly("NONE");
        assertThat(strict.featureValues().get(ProductFeature.CONTEXT_PINNING)).containsExactly("ON");
    }

    @Test
    void standardProfileHasCorrectValues() {
        GovernanceProfileDefinition standard = service.findByCode("STANDARD");
        assertThat(standard.featureValues().get(ProductFeature.INSTRUCTION_SOURCES)).containsExactlyInAnyOrder("INLINE", "GIT");
        assertThat(standard.featureValues().get(ProductFeature.BUDGET_OVERRIDE)).containsExactly("PER_SERVICE");
    }

    @Test
    void flexibleProfileHasCorrectValues() {
        GovernanceProfileDefinition flexible = service.findByCode("FLEXIBLE");
        assertThat(flexible.featureValues().get(ProductFeature.INSTRUCTION_SOURCES)).containsExactlyInAnyOrder("INLINE", "GIT");
        assertThat(flexible.featureValues().get(ProductFeature.CONTEXT_PINNING)).containsExactly("OFF");
        assertThat(flexible.featureValues().get(ProductFeature.BUDGET_OVERRIDE)).containsExactly("CONFIGURABLE");
    }

    @Test
    void getCurrentDefaultProfileCodeReturnsSeededDefault() {
        assertThat(service.getCurrentDefaultProfileCode()).isEqualTo("STANDARD");
    }

    @Test
    void findAllWithGroupsReturnsAllProfilesWithGroupsAndCurrentDefault() {
        List<GovernanceProfileResponse> responses = service.findAllWithGroups();
        assertThat(responses).hasSize(3);

        for (GovernanceProfileResponse r : responses) {
            assertThat(r.groups()).isNotNull();
            assertThat(r.groups()).isNotEmpty();
            assertThat(r.groups()).extracting(g -> g.code())
                    .containsExactlyInAnyOrder("AI_CONTEXT", "BUDGET");
        }

        GovernanceProfileResponse standard = responses.stream()
                .filter(r -> "STANDARD".equals(r.code()))
                .findFirst().orElseThrow();
        assertThat(standard.isCurrentDefault()).isTrue();

        responses.stream()
                .filter(r -> !"STANDARD".equals(r.code()))
                .forEach(r -> assertThat(r.isCurrentDefault()).isFalse());
    }

    @Test
    void findAllWithGroups_strictProfileHasCorrectFeatureValues() {
        List<GovernanceProfileResponse> responses = service.findAllWithGroups();
        GovernanceProfileResponse strict = responses.stream()
                .filter(r -> "STRICT".equals(r.code()))
                .findFirst().orElseThrow();

        var aiContextGroup = strict.groups().stream()
                .filter(g -> "AI_CONTEXT".equals(g.code()))
                .findFirst().orElseThrow();
        var instructionSources = aiContextGroup.features().stream()
                .filter(f -> "INSTRUCTION_SOURCES".equals(f.code()))
                .findFirst().orElseThrow();
        assertThat(instructionSources.currentValues()).containsExactly("GIT");

        var inlineScope = aiContextGroup.features().stream()
                .filter(f -> "INLINE_INSTRUCTIONS_SCOPE".equals(f.code()))
                .findFirst().orElseThrow();
        assertThat(inlineScope.currentValues()).containsExactly("NONE");

        var retention = aiContextGroup.features().stream()
                .filter(f -> "MANIFEST_RETENTION".equals(f.code()))
                .findFirst().orElseThrow();
        assertThat(retention.currentValues()).containsExactly("365_DAYS");
    }

    @Test
    void findAllWithGroups_flexibleProfileHasAllDataFeeds() {
        List<GovernanceProfileResponse> responses = service.findAllWithGroups();
        GovernanceProfileResponse flexible = responses.stream()
                .filter(r -> "FLEXIBLE".equals(r.code()))
                .findFirst().orElseThrow();

        var aiContextGroup = flexible.groups().stream()
                .filter(g -> "AI_CONTEXT".equals(g.code()))
                .findFirst().orElseThrow();
        var dataFeeds = aiContextGroup.features().stream()
                .filter(f -> "DATA_FEEDS".equals(f.code()))
                .findFirst().orElseThrow();
        assertThat(dataFeeds.currentValues()).containsExactlyInAnyOrder(
                "GIT", "WEB_CRAWL", "CONFLUENCE", "JIRA", "NOTION", "S3", "DB_SCHEMA");
    }

    @Test
    void setDefaultProfileChangesCurrentDefaultAndAudits() {
        long auditBefore = auditEventRepository.count();

        service.setDefaultProfile("STRICT", null);
        assertThat(service.getCurrentDefaultProfileCode()).isEqualTo("STRICT");
        assertThat(auditEventRepository.count()).isGreaterThan(auditBefore);

        // Reset for other tests
        service.setDefaultProfile("STANDARD", null);
        assertThat(service.getCurrentDefaultProfileCode()).isEqualTo("STANDARD");
    }

    @Test
    void setDefaultProfileSameProfileIsNoOp() {
        long auditBefore = auditEventRepository.count();
        String current = service.getCurrentDefaultProfileCode();

        service.setDefaultProfile(current, null);
        assertThat(auditEventRepository.count()).isEqualTo(auditBefore);
    }
}