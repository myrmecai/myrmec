package ai.myrmec.engine.spi.security;

import lombok.Builder;
import lombok.Value;

/**
 * Phase 9e — one detection from a {@link SecretLeakScanner}.
 *
 * <p>{@link #ruleId} is the scanner-defined name of the matching rule
 * (e.g. {@code AWS_ACCESS_KEY_ID}, {@code GITHUB_TOKEN}).
 * {@link #startIndex} / {@link #endIndex} are 0-based character offsets
 * into the scanned string, exclusive end. {@link #redactionMask} is
 * the suggested replacement string when the engine chooses to redact
 * rather than block; null means "use the engine default".</p>
 */
@Value
@Builder
public class SecretLeakHit {
    String ruleId;
    int startIndex;
    int endIndex;
    String redactionMask;
}
