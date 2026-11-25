// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._system.bootstrap.seeds;

import org.springframework.stereotype.Component;

import ai.myrmec.engine.seed.CatalogSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bootstrap component that seeds the {@code required/} reference data on
 * startup.
 *
 * <p>Runs once the application context is ready. A system setting
 * ({@link CatalogSeedService#REQUIRED_SEED_COMPLETED_KEY}) records that the
 * required tier has been applied; when already set, this bootstrap is a
 * no-op. Clearing the flag re-runs the (idempotent) seed.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SystemDataSeedService {

    private final CatalogSeedService catalogSeedService;

    public void onApplicationReady() {
        if (catalogSeedService.isRequiredSeedCompleted()) {
            log.debug("Required seed data already applied, skipping");
            return;
        }
        try {
            catalogSeedService.seedRequired();
        } catch (Exception e) {
            log.error("Failed to seed required reference data: {}", e.getMessage(), e);
            throw new IllegalStateException(
                    "Cannot start: failed to seed required reference data", e);
        }
    }
}
