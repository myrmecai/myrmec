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
 * Integration tests for GovernanceProfileService — verifies seeded
 * profiles are loaded, queryable, and the compare matrix / set-default
 * flows work correctly.
 */
class GovernanceProfileServiceTest extends IntegrationTestBase {

    @Autowired
    private GovernanceProfileService service;

    @Test
    void seededProfilesArePresent() {
        var profiles = service.findAll();
        assertThat(profiles).hasSize(3);
        assertThat(profiles).extracting(GovernanceProfile::getCode)
                .containsExactlyInAnyOrder("STRICT", "STANDARD", "FLEXIBLE");
    }

    @Test
    void strictProfileHasCorrectPolicies() {
        var policies = service.getEffectivePolicies("STRICT");
        assertThat(policies).containsKey("inlineInstructions");
        assertThat(policies.get("inlineInstructions")).isEqualTo("NOT_ALLOWED");
        assertThat(policies.get("contextPinning")).isEqualTo("PINNED_AT_START");
    }

    @Test
    void standardProfileHasCorrectPolicies() {
        var policies = service.getEffectivePolicies("STANDARD");
        assertThat(policies.get("inlineInstructions")).isEqualTo("PROJECT_SERVICE");
        assertThat(policies.get("budgetEnforcement")).isEqualTo("HARD_CAP_PER_SERVICE");
    }

    @Test
    void flexibleProfileHasCorrectPolicies() {
        var policies = service.getEffectivePolicies("FLEXIBLE");
        assertThat(policies.get("inlineInstructions")).isEqualTo("ALL_SCOPES");
        assertThat(policies.get("contextPinning")).isEqualTo("IMMEDIATE_EFFECT");
    }

    @Test
    void findByCode_returnsProfile() {
        var profile = service.findByCode("STANDARD");
        assertThat(profile.getName()).isEqualTo("Standard");
        assertThat(profile.getIsBuiltIn()).isTrue();
        assertThat(profile.getIsSystem()).isTrue();
    }

    @Test
    void getCurrentDefaultProfileCode_returnsSeededDefault() {
        assertThat(service.getCurrentDefaultProfileCode()).isEqualTo("STANDARD");
    }

    @Test
    void findAllWithGroups_returnsAllProfilesWithGroupsAndCurrentDefault() {
        List<GovernanceProfileResponse> responses = service.findAllWithGroups();
        assertThat(responses).hasSize(3);

        // Each response should have groups populated
        for (GovernanceProfileResponse r : responses) {
            assertThat(r.groups()).isNotNull();
            assertThat(r.groups()).isNotEmpty();
            // Should have AI_CONTEXT and BUDGET groups
            assertThat(r.groups()).extracting(g -> g.code())
                    .containsExactlyInAnyOrder("AI_CONTEXT", "BUDGET");
        }

        // STANDARD should be the current default
        GovernanceProfileResponse standard = responses.stream()
                .filter(r -> "STANDARD".equals(r.code()))
                .findFirst().orElseThrow();
        assertThat(standard.isCurrentDefault()).isTrue();

        // STRICT and FLEXIBLE should not be current default
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

        // Find the INSTRUCTION_SOURCES feature in the AI_CONTEXT group
        var aiContextGroup = strict.groups().stream()
                .filter(g -> "AI_CONTEXT".equals(g.code()))
                .findFirst().orElseThrow();
        var instructionSources = aiContextGroup.features().stream()
                .filter(f -> "INSTRUCTION_SOURCES".equals(f.code()))
                .findFirst().orElseThrow();
        assertThat(instructionSources.currentValues()).containsExactly("GIT");

        // INLINE_INSTRUCTIONS_SCOPE should be NONE
        var inlineScope = aiContextGroup.features().stream()
                .filter(f -> "INLINE_INSTRUCTIONS_SCOPE".equals(f.code()))
                .findFirst().orElseThrow();
        assertThat(inlineScope.currentValues()).containsExactly("NONE");

        // MANIFEST_RETENTION should be 365_DAYS
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
    void setDefaultProfile_changesCurrentDefaultAndAudits() {
        long auditBefore = auditEventRepository.count();

        // Change to STRICT
        service.setDefaultProfile("STRICT", null);

        // Verify the setting was updated
        assertThat(service.getCurrentDefaultProfileCode()).isEqualTo("STRICT");

        // Verify audit event was recorded
        assertThat(auditEventRepository.count()).isGreaterThan(auditBefore);

        // Reset to STANDARD for other tests
        service.setDefaultProfile("STANDARD", null);
        assertThat(service.getCurrentDefaultProfileCode()).isEqualTo("STANDARD");
    }

    @Test
    void setDefaultProfile_sameProfileIsNoOp() {
        long auditBefore = auditEventRepository.count();
        String current = service.getCurrentDefaultProfileCode();

        // Setting to the same value should be a no-op
        service.setDefaultProfile(current, null);

        // No new audit events
        assertThat(auditEventRepository.count()).isEqualTo(auditBefore);
    }
}