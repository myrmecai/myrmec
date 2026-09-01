// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine._system.config;

import ai.myrmec.engine.spi.license.LicenseService;
import ai.myrmec.engine.spi.license.LicenseSnapshot;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;


/**
 * Publishes the active license tier and Enterprise feature set under
 * {@code actuator/info} so the E2E harness can assert ENTERPRISE vs COMMUNITY
 * state without relying on private endpoints.
 */
@Component
public class LicenseInfoContributor implements InfoContributor {

    private final LicenseService licenseService;

    public LicenseInfoContributor(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Override
    public void contribute(Info.Builder builder) {
        LicenseSnapshot snapshot = licenseService.snapshot();
        builder.withDetail("myrmec", java.util.Map.of(
                "tier", snapshot.tier().name(),
                "features", snapshot.features()
                        .stream()
                        .map(Enum::name)
                        .toList()
        ));
    }
}
