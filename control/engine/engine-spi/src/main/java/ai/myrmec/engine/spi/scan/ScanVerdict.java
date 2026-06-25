package ai.myrmec.engine.spi.scan;

/** Outcome of a {@link ContentScanProvider#scan} call. */
public enum ScanVerdict {

    /** No threat detected; the attachment may proceed. */
    CLEAN,

    /** A threat was detected; the attachment must be quarantined / rejected. */
    INFECTED,

    /**
     * The scanner could not complete (backend unreachable, timeout, …). Call
     * sites fail closed — an attachment that could not be scanned is treated
     * as not-yet-safe and is rejected, never silently passed.
     */
    ERROR
}
