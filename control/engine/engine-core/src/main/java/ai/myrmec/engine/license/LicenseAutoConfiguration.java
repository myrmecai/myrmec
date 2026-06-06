package ai.myrmec.engine.license;

import ai.myrmec.engine.spi.license.LicenseService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link CommunityLicenseService} as the default {@link LicenseService}
 * bean under {@link ConditionalOnMissingBean}. The Enterprise jar publishes its
 * own {@code JwtLicenseService} bean in an auto-configuration annotated
 * {@code @AutoConfigureBefore(LicenseAutoConfiguration.class)}, which causes
 * Spring to skip the {@code communityLicenseService} bean and use the
 * Enterprise implementation instead.
 */
@AutoConfiguration
public class LicenseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LicenseService.class)
    public LicenseService communityLicenseService() {
        return new CommunityLicenseService();
    }
}
