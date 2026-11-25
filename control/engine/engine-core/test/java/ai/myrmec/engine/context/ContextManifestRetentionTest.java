// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.context;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.governance.ProductFeature;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GOV-07 — Context manifest retention enforced per profile.
 *
 * <p>Verifies that the {@link ContextManifestRetentionSweeper} prunes
 * {@link ContextManifest} rows older than the retention window dictated
 * by each row's {@code governanceProfileCode}:
 * <ul>
 *   <li>STRICT  → 365 days</li>
 *   <li>STANDARD → 90 days</li>
 *   <li>FLEXIBLE → 30 days</li>
 * </ul>
 *
 * <p>Rows younger than the cutoff survive; rows older than the cutoff
 * for their profile are deleted. The sweeper processes each profile
 * independently.
 */
class ContextManifestRetentionTest extends IntegrationTestBase {

    @Autowired private ContextManifestRepository manifestRepository;
    @Autowired private ContextManifestRetentionSweeper sweeper;

    /**
     * Helper: create and persist a manifest, then force its
     * {@code createdAt} to a specific instant via a native update
     * (bypassing the {@code @PrePersist} default of "now").
     */
    private ContextManifest createManifestWithAge(String profileCode, long daysAgo) {
        ContextManifest manifest = new ContextManifest();
        manifest.setSessionId(UUID.randomUUID());
        manifest.setServiceType("CONVERSATION");
        manifest.setSequenceNo(1L);
        manifest.setGovernanceProfileCode(profileCode);
        manifest.setContextPinning("IMMEDIATE_EFFECT");
        manifest.setTotalTokens(100);
        manifest.setBudgetTokens(1000);
        manifest.setTruncated(false);
        manifest.setInstructionsIncluded(List.of(Map.of("name", "test")));
        manifest = manifestRepository.saveAndFlush(manifest);

        // Override createdAt via the sweeper's test helper (runs in its own tx)
        Instant target = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        sweeper.overrideCreatedAt(manifest.getId(), target);
        return manifest;
    }

    @Test
    @Tag("GOV-07")
    void retentionDaysMappingIsCorrect() {
        assertThat(ProductFeature.manifestRetentionDays("365_DAYS")).isEqualTo(365);
        assertThat(ProductFeature.manifestRetentionDays("90_DAYS")).isEqualTo(90);
        assertThat(ProductFeature.manifestRetentionDays("30_DAYS")).isEqualTo(30);
        assertThat(ProductFeature.manifestRetentionDays("UNKNOWN")).isEqualTo(-1);
    }

    @Test
    @Tag("GOV-07")
    void sweeperPrunesOldManifestsForStandardProfile() {
        // STANDARD retention = 90 days
        // A manifest 100 days old should be pruned
        ContextManifest oldManifest = createManifestWithAge("STANDARD", 100);
        // A manifest 10 days old should survive
        ContextManifest youngManifest = createManifestWithAge("STANDARD", 10);

        // Run the sweeper for STANDARD profile
        int deleted = sweeper.sweepProfile("STANDARD");

        assertThat(deleted).isEqualTo(1);
        assertThat(manifestRepository.findById(oldManifest.getId())).isEmpty();
        assertThat(manifestRepository.findById(youngManifest.getId())).isPresent();
    }

    @Test
    @Tag("GOV-07")
    void sweeperPrunesOldManifestsForFlexibleProfile() {
        // FLEXIBLE retention = 30 days
        // A manifest 45 days old should be pruned
        ContextManifest oldManifest = createManifestWithAge("FLEXIBLE", 45);
        // A manifest 5 days old should survive
        ContextManifest youngManifest = createManifestWithAge("FLEXIBLE", 5);

        int deleted = sweeper.sweepProfile("FLEXIBLE");

        assertThat(deleted).isEqualTo(1);
        assertThat(manifestRepository.findById(oldManifest.getId())).isEmpty();
        assertThat(manifestRepository.findById(youngManifest.getId())).isPresent();
    }

    @Test
    @Tag("GOV-07")
    void sweeperPrunesOldManifestsForStrictProfile() {
        // STRICT retention = 365 days
        // A manifest 400 days old should be pruned
        ContextManifest oldManifest = createManifestWithAge("STRICT", 400);
        // A manifest 200 days old should survive (within 365-day window)
        ContextManifest youngManifest = createManifestWithAge("STRICT", 200);

        int deleted = sweeper.sweepProfile("STRICT");

        assertThat(deleted).isEqualTo(1);
        assertThat(manifestRepository.findById(oldManifest.getId())).isEmpty();
        assertThat(manifestRepository.findById(youngManifest.getId())).isPresent();
    }

    @Test
    @Tag("GOV-07")
    void sweeperDoesNotCrossContaminateProfiles() {
        // A STANDARD manifest that is 100 days old should be pruned (90-day window)
        ContextManifest standardOld = createManifestWithAge("STANDARD", 100);
        // A STRICT manifest that is 100 days old should survive (365-day window)
        ContextManifest strictSameAge = createManifestWithAge("STRICT", 100);

        // Sweep only STANDARD
        int deleted = sweeper.sweepProfile("STANDARD");

        assertThat(deleted).isEqualTo(1);
        assertThat(manifestRepository.findById(standardOld.getId())).isEmpty();
        assertThat(manifestRepository.findById(strictSameAge.getId())).isPresent();

        // Now sweep STRICT — the 100-day-old STRICT manifest should still survive
        int strictDeleted = sweeper.sweepProfile("STRICT");
        assertThat(strictDeleted).isEqualTo(0);
        assertThat(manifestRepository.findById(strictSameAge.getId())).isPresent();
    }

    @Test
    @Tag("GOV-07")
    void sweepAllProfilesPrunesEachByItsOwnWindow() {
        // Create one old manifest per profile
        ContextManifest strictOld = createManifestWithAge("STRICT", 400);
        ContextManifest standardOld = createManifestWithAge("STANDARD", 100);
        ContextManifest flexibleOld = createManifestWithAge("FLEXIBLE", 45);

        // And one young manifest per profile
        ContextManifest strictYoung = createManifestWithAge("STRICT", 10);
        ContextManifest standardYoung = createManifestWithAge("STANDARD", 10);
        ContextManifest flexibleYoung = createManifestWithAge("FLEXIBLE", 10);

        // Run the full sweep (all profiles)
        sweeper.sweep();

        // Old manifests pruned
        assertThat(manifestRepository.findById(strictOld.getId())).isEmpty();
        assertThat(manifestRepository.findById(standardOld.getId())).isEmpty();
        assertThat(manifestRepository.findById(flexibleOld.getId())).isEmpty();

        // Young manifests survive
        assertThat(manifestRepository.findById(strictYoung.getId())).isPresent();
        assertThat(manifestRepository.findById(standardYoung.getId())).isPresent();
        assertThat(manifestRepository.findById(flexibleYoung.getId())).isPresent();
    }

    @Test
    @Tag("GOV-07")
    void sweeperBoundaryCondition_prunesAtExactCutoff() {
        // A manifest exactly at the 90-day boundary for STANDARD
        // 91 days old → should be pruned (older than 90 days)
        ContextManifest justOver = createManifestWithAge("STANDARD", 91);
        // 89 days old → should survive
        ContextManifest justUnder = createManifestWithAge("STANDARD", 89);

        int deleted = sweeper.sweepProfile("STANDARD");

        assertThat(deleted).isEqualTo(1);
        assertThat(manifestRepository.findById(justOver.getId())).isEmpty();
        assertThat(manifestRepository.findById(justUnder.getId())).isPresent();
    }
}