package ai.myrmec.engine.spi.security;

import java.util.List;

/**
 * Phase 9e — pluggable scanner that inspects outbound text leaving the
 * engine (chat replies, tool outputs, retrieval payloads, etc.) for
 * leaked secrets.
 *
 * <p>Implementations may be provided by {@code engine-core} (free,
 * regex-based) or by enterprise-only modules (entropy ML, vendor APIs).
 * The engine consults the loaded scanner via the {@code SecretLeakService}
 * just before persisting / forwarding the text; behaviour on a hit is
 * controlled by configuration (block | redact | warn).</p>
 */
public interface SecretLeakScanner {

    /**
     * Inspect {@code text} and return a list of detected hits. An
     * empty list means no leak was found. The contract is read-only —
     * implementations MUST NOT mutate the input.
     *
     * @param text the candidate output string (may be null / empty;
     *             implementations should treat as no-hit)
     * @return non-null list of hits
     */
    List<SecretLeakHit> scan(String text);

    /** Stable identifier for the implementation (for logs + audit). */
    String getId();
}
