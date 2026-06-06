package ai.myrmec.engine.spi.license;

/**
 * Coarse-grained product tier reported by a {@link LicenseService}.
 *
 * <p>Mapped from the {@code product} claim of a license JWT. Used for telemetry,
 * billing dashboards, and humane logs ("Running in COMMUNITY tier"). Feature gating
 * itself is by {@link LicenseFeature}, never by tier.
 */
public enum LicenseTier {

    /** No Enterprise jar or no valid license. Default behaviour for OSS users. */
    COMMUNITY,

    /** Enterprise jar loaded with a valid license granting the base Enterprise feature set. */
    ENTERPRISE,

    /** Enterprise jar loaded with a valid license granting the full Enterprise feature set. */
    ENTERPRISE_PLUS
}
