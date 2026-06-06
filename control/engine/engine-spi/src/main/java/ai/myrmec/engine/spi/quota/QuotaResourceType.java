package ai.myrmec.engine.spi.quota;

/** Quota-able resource units. */
public enum QuotaResourceType {
    /** Raw token count (any direction). */
    TOKENS,
    /** Cost in USD &times; 100 (cents). Stored as bigint so 64-bit safe. */
    COST_USD_CENTS
}
