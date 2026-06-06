package ai.myrmec.engine.spi.license;

/**
 * Authoritative gate for Enterprise feature activation.
 *
 * <p>Every Enterprise bean that ships behind a {@link LicenseFeature} consults this
 * service on each public method entry and delegates to its bundled Community counterpart
 * when {@link #isLicensedFor(LicenseFeature)} returns {@code false}. This is how the
 * engine degrades gracefully when a license expires at runtime without restart.
 *
 * <p>Engine-core ships {@code CommunityLicenseService} which always reports
 * {@link LicenseTier#COMMUNITY} and answers {@code false} for every feature.
 * The Enterprise jar replaces this bean with a JWT-backed implementation that
 * watches the license directory and rolls licenses through the
 * {@link LicenseState} state machine.
 *
 * <p>Implementations must be thread-safe; the engine queries them from any thread.
 */
public interface LicenseService {

    /**
     * @return {@code true} iff at least one currently effective license entry grants
     *         {@code feature}. Returns {@code false} on any failure (no license, expired,
     *         revoked, signature mismatch); the engine treats absence of permission as
     *         the default safe state.
     */
    boolean isLicensedFor(LicenseFeature feature);

    /**
     * @return the highest tier reported by any currently effective license entry, or
     *         {@link LicenseTier#COMMUNITY} when none is effective.
     */
    LicenseTier tier();

    /**
     * @return immutable snapshot of all known license entries (effective and otherwise)
     *         for telemetry, dashboards and audit. Cheap to call.
     */
    LicenseSnapshot snapshot();
}
