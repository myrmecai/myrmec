package ai.myrmec.engine.spi.scan;

/**
 * Pluggable malware / content scanner that gates every uploaded attachment
 * <em>before</em> its bytes are extracted, stored, or exposed to an agent
 * (#104). This is a hard gate for the attachments feature (#103): an upload
 * that does not pass a scan never reaches the model or the workspace.
 *
 * <p>{@code engine-core} ships a bundled, dependency-free implementation
 * (signature + EICAR aware). Enterprise / air-gapped deployments contribute a
 * {@code @Component} that fronts ClamAV, an on-prem engine, or a vendor API;
 * the bundled bean steps aside when another is present
 * ({@code @ConditionalOnMissingBean}).</p>
 *
 * <p>The contract is read-only — implementations MUST NOT mutate the input
 * bytes. A scanner that cannot reach its backend MUST return
 * {@link ScanVerdict#ERROR} (fail-closed at the call site) rather than
 * silently passing the content.</p>
 */
public interface ContentScanProvider {

    /**
     * Scan the candidate attachment bytes.
     *
     * @param content   the raw uploaded bytes (non-null)
     * @param filename  the client-supplied filename (informational; may be null)
     * @param mediaType the declared MIME type (informational; may be null)
     * @return a non-null {@link ScanResult}
     */
    ScanResult scan(byte[] content, String filename, String mediaType);

    /** Stable identifier for the implementation (for logs + audit). */
    String getId();
}
