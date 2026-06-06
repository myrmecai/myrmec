package ai.myrmec.engine.spi.license;

/**
 * Lifecycle state of a single license entry as evaluated by the {@link LicenseService}.
 *
 * <p>An {@link LicenseEntry} transitions through these states based on signature
 * verification, clock comparison against {@code expiresAt}, and revocation list checks.
 *
 * <pre>
 *               +-----------+
 *               |  PENDING  |  signature ok, not yet effective
 *               +-----+-----+
 *                     |
 *                     v
 *               +-----------+
 *               |  ACTIVE   |  in force right now
 *               +-----+-----+
 *                     |
 *           expiresAt passed
 *                     |
 *                     v
 *               +-----------+        grace period          +-----------+
 *               |   GRACE   | --------------------------&gt;  |  EXPIRED  |
 *               +-----+-----+                              +-----------+
 *                     |
 *                CRL match
 *                     v
 *               +-----------+
 *               |  REVOKED  |
 *               +-----------+
 * </pre>
 */
public enum LicenseState {

    /** Signature verified but {@code effectiveFrom} is in the future. */
    PENDING,

    /** Currently in force. {@code LicenseService.isLicensedFor(feature)} may return true. */
    ACTIVE,

    /** Past {@code expiresAt} but within the configured grace window; logs WARN; still acts as ACTIVE. */
    GRACE,

    /** Past grace; treated as no license. */
    EXPIRED,

    /** Listed in a current certificate revocation list; treated as no license regardless of expiry. */
    REVOKED
}
