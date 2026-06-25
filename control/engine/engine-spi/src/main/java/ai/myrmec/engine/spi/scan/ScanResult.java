package ai.myrmec.engine.spi.scan;

/**
 * Result of a content scan. {@code threat} names the detected signature when
 * {@link #verdict()} is {@link ScanVerdict#INFECTED}, or the failure cause
 * when it is {@link ScanVerdict#ERROR}; it is null for a {@link
 * ScanVerdict#CLEAN} result.
 *
 * @param verdict    the scan outcome (non-null)
 * @param scannerId  identifier of the scanner that produced this result
 * @param threat     detected signature / failure cause, or null when clean
 */
public record ScanResult(ScanVerdict verdict, String scannerId, String threat) {

    public static ScanResult clean(String scannerId) {
        return new ScanResult(ScanVerdict.CLEAN, scannerId, null);
    }

    public static ScanResult infected(String scannerId, String threat) {
        return new ScanResult(ScanVerdict.INFECTED, scannerId, threat);
    }

    public static ScanResult error(String scannerId, String cause) {
        return new ScanResult(ScanVerdict.ERROR, scannerId, cause);
    }

    public boolean isClean() {
        return verdict == ScanVerdict.CLEAN;
    }
}
