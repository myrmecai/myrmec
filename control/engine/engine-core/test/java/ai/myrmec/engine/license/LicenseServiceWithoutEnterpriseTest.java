package ai.myrmec.engine.license;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.crypto.EncryptionService;
import ai.myrmec.engine.spi.license.LicenseFeature;
import ai.myrmec.engine.spi.license.LicenseService;
import ai.myrmec.engine.spi.license.LicenseSnapshot;
import ai.myrmec.engine.spi.license.LicenseTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 verification: without the Enterprise jar on the classpath the engine
 * must (a) auto-configure the Community LicenseService and the basic
 * EncryptionService through their respective auto-configs, (b) report
 * {@link LicenseTier#COMMUNITY}, (c) deny every {@link LicenseFeature}, and
 * (d) still encrypt/decrypt round-trip correctly via the SPI interface.
 */
class LicenseServiceWithoutEnterpriseTest extends IntegrationTestBase {

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private EncryptionService encryptionService;

    @Test
    void licenseServiceBeanIsCommunityImpl() {
        assertNotNull(licenseService, "LicenseService must be auto-configured by engine-core");
        assertInstanceOf(CommunityLicenseService.class, licenseService,
                "Without engine-enterprise on the classpath the Community impl wins");
    }

    @Test
    void communityTierIsReportedAndNoFeaturesAreLicensed() {
        assertEquals(LicenseTier.COMMUNITY, licenseService.tier());
        for (LicenseFeature feature : LicenseFeature.values()) {
            assertFalse(licenseService.isLicensedFor(feature),
                    "Community must report " + feature + " as unlicensed");
        }
    }

    @Test
    void snapshotIsEmptyForCommunityInstall() {
        LicenseSnapshot snapshot = licenseService.snapshot();
        assertNotNull(snapshot);
        assertEquals(LicenseTier.COMMUNITY, snapshot.tier());
        assertTrue(snapshot.features().isEmpty(), "Community snapshot grants zero features");
        assertTrue(snapshot.entries().isEmpty(), "Community snapshot lists zero entries");
        assertNotNull(snapshot.evaluatedAt());
    }

    @Test
    void encryptionServiceSpiResolvesToBasicImplAndRoundTrips() {
        assertNotNull(encryptionService, "EncryptionService SPI must be auto-configured");
        String plaintext = "myrmec-phase-2-roundtrip";
        byte[] cipher = encryptionService.encrypt(plaintext);
        assertNotNull(cipher);
        assertEquals(plaintext, encryptionService.decrypt(cipher));
    }
}
