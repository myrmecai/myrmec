package ai.myrmec.engine.spi.license;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Aggregated, point-in-time view of every license file the engine currently honours.
 *
 * <p>Surfaced by {@link LicenseService#snapshot()} for {@code /actuator/info},
 * admin UIs, and audit log entries. Treat as immutable; callers must not mutate the
 * collections.
 *
 * @param tier            the highest tier across all currently effective entries
 *                        (or {@link LicenseTier#COMMUNITY} if no entry is effective).
 * @param features        union of features granted by all currently effective entries.
 * @param entries         every entry the loader knows about, including PENDING /
 *                        EXPIRED / REVOKED ones, for operator visibility.
 * @param evaluatedAt     when this snapshot was produced (engine wall-clock).
 */
public record LicenseSnapshot(
        LicenseTier tier,
        Set<LicenseFeature> features,
        List<LicenseEntry> entries,
        Instant evaluatedAt
) {
}
