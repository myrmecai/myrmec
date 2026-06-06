package ai.myrmec.engine.license;

import ai.myrmec.engine.spi.license.LicenseEntry;
import ai.myrmec.engine.spi.license.LicenseFeature;
import ai.myrmec.engine.spi.license.LicenseService;
import ai.myrmec.engine.spi.license.LicenseSnapshot;
import ai.myrmec.engine.spi.license.LicenseTier;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Default {@link LicenseService}. Always reports {@link LicenseTier#COMMUNITY},
 * licenses no Enterprise features, and exposes an empty entry list. Replaced at
 * runtime by an Enterprise-supplied {@code JwtLicenseService} bean that runs in
 * an {@code @AutoConfigureBefore({@code LicenseAutoConfiguration.class})}
 * configuration when {@code engine-enterprise} is on the classpath.
 *
 * <p>Returning {@code false} for every feature is the safe default: the engine
 * never accidentally enables paid functionality in an unlicensed install.
 */
public class CommunityLicenseService implements LicenseService {

    @Override
    public boolean isLicensedFor(LicenseFeature feature) {
        return false;
    }

    @Override
    public LicenseTier tier() {
        return LicenseTier.COMMUNITY;
    }

    @Override
    public LicenseSnapshot snapshot() {
        return new LicenseSnapshot(
                LicenseTier.COMMUNITY,
                Set.of(),
                List.<LicenseEntry>of(),
                Instant.now()
        );
    }
}
