package ai.myrmec.engine.spi.license;

/**
 * Enumerable Enterprise capability gated by a license entitlement.
 *
 * <p>Each value is a stable identifier that appears in license JWT {@code features} claims
 * and in {@link ConditionalOnLicense} annotations. Names are part of the public SPI contract:
 * once shipped, they may not be renamed (only deprecated and replaced).
 *
 * <p>See {@code docs/design/00-foundation-and-enterprise-loading.md} §4 for the
 * canonical interface inventory that maps each feature to its SPI and Enterprise impl.
 */
public enum LicenseFeature {

    /** Tamper-evident audit chain, persisted snapshots, retention enforcement. */
    FORENSIC_AUDIT,

    /** SIEM/Syslog/CEF and OTLP outbound event export. */
    SIEM_EXPORT,

    /** Data classification enforcement (block/redact based on labels). */
    CLASSIFICATION_GATES,

    /** PII detection and DLP egress controls beyond regex baselines. */
    DLP_PII,

    /** Group/reservation/burn-rate quota policies beyond the per-scope basic engine. */
    ADVANCED_QUOTAS,

    /** Hot Postgres + cold object-store tiered snapshot payload storage. */
    TIERED_STORAGE,

    /** Multi-currency, cost-centre-tagged chargeback report exports. */
    CHARGEBACK_REPORTS
}
