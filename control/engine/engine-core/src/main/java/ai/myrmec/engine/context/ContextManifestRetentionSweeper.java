// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.context;

import ai.myrmec.engine.governance.BuiltInGovernanceProfile;
import ai.myrmec.engine.governance.GovernanceProfileDefinition;
import ai.myrmec.engine.governance.GovernanceProfileProvider;
import ai.myrmec.engine.governance.ProductFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Enforces the {@link ProductFeature#MANIFEST_RETENTION} governance
 * feature by periodically pruning {@link ContextManifest} rows whose
 * {@code createdAt} is older than the retention window dictated by
 * their own {@code governanceProfileCode}.
 *
 * <p>Each profile code (STRICT, STANDARD, FLEXIBLE) has its own
 * retention window (365, 90, 30 days respectively). The sweeper
 * iterates over all built-in profiles and deletes rows per-profile
 * in separate {@link Propagation#REQUIRES_NEW} transactions so a
 * failure on one profile never blocks the others.
 *
 * <p>Modeled on {@link ai.myrmec.engine.conversation.ApprovalExpirySweeper}.
 * Disabled by default in tests via {@code myrmec.manifest.retention-sweeper.enabled}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContextManifestRetentionSweeper {

    private final ContextManifestRepository manifestRepository;
    private final GovernanceProfileProvider profileProvider;

    /** Self-reference for proxy-based @Transactional invocation from sweep(). */
    @Autowired @Lazy
    private ContextManifestRetentionSweeper self;

    @Value("${myrmec.manifest.retention-sweeper.enabled:true}")
    private boolean enabled;

    /**
     * Run the retention sweep. In production this is called by the
     * {@code @Scheduled} timer; in tests it can be called directly.
     */
    @Scheduled(fixedRateString = "${myrmec.manifest.retention-sweeper.interval-ms:300000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        for (BuiltInGovernanceProfile profile : BuiltInGovernanceProfile.values()) {
            try {
                // Call through the proxy so @Transactional(REQUIRES_NEW) is honoured
                self.sweepProfile(profile.code());
            } catch (Exception e) {
                log.warn("Manifest retention sweep failed for profile {}: {}",
                        profile.code(), e.getMessage(), e);
            }
        }
    }

    /**
     * Prune manifests for a single profile code. Runs in its own
     * transaction so a failure on one profile never blocks the others.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int sweepProfile(String profileCode) {
        GovernanceProfileDefinition profile = profileProvider.resolve(profileCode);
        Set<String> retentionValues = profile.featureValues().get(ProductFeature.MANIFEST_RETENTION);
        if (retentionValues == null || retentionValues.isEmpty()) {
            log.debug("Profile {} has no MANIFEST_RETENTION value — skipping", profileCode);
            return 0;
        }
        String retentionValue = retentionValues.iterator().next();
        int days = ProductFeature.manifestRetentionDays(retentionValue);
        if (days < 0) {
            log.warn("Profile {} has unrecognised MANIFEST_RETENTION value '{}' — skipping",
                    profileCode, retentionValue);
            return 0;
        }

        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        int deleted = manifestRepository.deleteByProfileAndCutoff(profileCode, cutoff);
        if (deleted > 0) {
            log.info("Manifest retention sweep: deleted {} rows for profile {} (cutoff {}, retention={})",
                    deleted, profileCode, cutoff, retentionValue);
        }
        return deleted;
    }

    /**
     * Convenience method for tests: sweep a specific profile with a
     * custom cutoff instead of computing from "now". Does not check
     * the {@code enabled} flag.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int sweepProfileWithCutoff(String profileCode, Instant cutoff) {
        return manifestRepository.deleteByProfileAndCutoff(profileCode, cutoff);
    }

    /**
     * Test helper: override the {@code createdAt} on a manifest row.
     * Runs in its own transaction because the repository
     * {@code @Modifying} query requires a transaction context and
     * test methods may not be {@code @Transactional}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void overrideCreatedAt(java.util.UUID manifestId, Instant createdAt) {
        manifestRepository.updateCreatedAt(manifestId, createdAt);
    }
}