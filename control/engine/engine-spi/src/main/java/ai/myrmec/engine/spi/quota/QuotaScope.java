package ai.myrmec.engine.spi.quota;

/** Quota scope tiers: ORG &rarr; GROUP &rarr; PROJECT &rarr; USER. */
public enum QuotaScope {
    ORG,
    GROUP,
    PROJECT,
    USER
}
