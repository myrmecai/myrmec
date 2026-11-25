// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._system.bootstrap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import ai.myrmec.engine._system.bootstrap.seeds.AdminUserSeedService;
import ai.myrmec.engine._system.bootstrap.seeds.DevDemoUserSeedService;
import ai.myrmec.engine._system.bootstrap.seeds.SystemDataSeedService;
import ai.myrmec.engine._system.bootstrap.seeds.TestDataSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bootstrap component that seeds the {@code required/} reference data on
 * startup.
 *
 * <p>Runs once the application context is ready. A system setting
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SystemBootstrap {

    private final SystemDataSeedService systemDataSeedService;
    private final AdminUserSeedService adminUserSeedService;
    private final TestDataSeedService testDataSeedService;
    private final DevDemoUserSeedService devDemoUserSeedService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Initializing the application");
        systemDataSeedService.onApplicationReady();
        adminUserSeedService.onApplicationReady();
        testDataSeedService.onApplicationReady();
        devDemoUserSeedService.onApplicationReady();
        log.info("Application initialization complete");
    }
}
