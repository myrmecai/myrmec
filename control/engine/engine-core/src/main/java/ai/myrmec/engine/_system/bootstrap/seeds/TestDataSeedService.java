// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._system.bootstrap.seeds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ai.myrmec.engine.seed.OptionalDataSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bootstrap component that seeds the {@code optional/} test data on startup,
 * gated by the {@code MYRMEC_LOAD_TEST_DATA} environment variable.
 *
 * <p>When {@code MYRMEC_LOAD_TEST_DATA=true}, this seeds test providers,
 * secrets, connection configs (and later models, agent profiles and a test
 * assistant). In all other cases it is a no-op.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TestDataSeedService {

    private final OptionalDataSeedService optionalDataSeedService;

    @Value("${myrmec.load-test-data:false}")
    private boolean loadTestData;

    public void onApplicationReady() {
        if (!loadTestData) {
            log.debug("MYRMEC_LOAD_TEST_DATA is not enabled; skipping optional test-data seed");
            return;
        }
        try {
            optionalDataSeedService.seedTestData();
        } catch (Exception e) {
            log.error("Failed to seed optional test data: {}", e.getMessage(), e);
            throw new IllegalStateException(
                    "Cannot start: failed to seed optional test data", e);
        }
    }
}
