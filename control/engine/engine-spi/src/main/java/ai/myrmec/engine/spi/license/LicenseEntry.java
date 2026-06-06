package ai.myrmec.engine.spi.license;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable, parsed view of a single license file as evaluated at a moment in time.
 *
 * <p>One license file (signed JWT) on disk produces zero or one {@code LicenseEntry} after
 * loading and verification. The set of currently effective entries is what the
 * {@link LicenseService} aggregates into a {@link LicenseSnapshot}.
 *
 * @param keyVersion       the {@code kid} (key id) that signed this license; the engine
 *                         must have a matching public key in its trusted-keys list.
 * @param customerId       customer identifier from the {@code sub} claim.
 * @param productId        product identifier from the {@code product} claim.
 * @param tier             tier granted by this license entry.
 * @param features         feature set granted by this license entry.
 * @param effectiveFrom    earliest instant at which the entry is in force.
 * @param expiresAt        last instant at which the entry is in force (before grace).
 * @param state            current state when the snapshot was taken.
 * @param source           opaque identifier describing where this entry came from
 *                         (e.g. file path, KMS slot) for operator logs.
 */
public record LicenseEntry(
        String keyVersion,
        String customerId,
        String productId,
        LicenseTier tier,
        Set<LicenseFeature> features,
        Instant effectiveFrom,
        Instant expiresAt,
        LicenseState state,
        String source
) {
}
