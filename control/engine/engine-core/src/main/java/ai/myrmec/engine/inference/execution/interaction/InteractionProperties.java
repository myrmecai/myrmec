// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

/**
 * section 22.2 interaction policy defaults and the tighten-only domain.
 *
 * <p>These are the initial implementation defaults shipped on orchestration
 * {@code session.open} payloads; project policy may only TIGHTEN the global
 * bounds. Invalid policy fails initialization. This type is the engine-side
 * contract authority for Task 1 — persistence wiring and the policy-source
 * configuration belong to Task 2+, but validation semantics are fixed here.</p>
 */
public final class InteractionProperties {

    /** section 22.2: V1 wire version of the interaction contract. */
    public static final int VERSION = 1;

    // ---- The implementation defaults (section 22.2 verbatim) ----
    public static final int DEFAULT_IDLE_RESUME_AFTER_SECONDS = 300;
    public static final int DEFAULT_RESPONSE_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_MAX_INPUT_BYTES = 16384;
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 65536;
    public static final int DEFAULT_MAX_MODEL_ITERATIONS = 8;
    public static final int DEFAULT_MAX_HISTORY_BYTES = 262144;
    public static final int DEFAULT_TRANSCRIPT_RETENTION_DAYS = 30;

    // ---- The tighten-only bounds (section 22.2) ----
    public static final int MIN_IDLE_RESUME_AFTER_SECONDS = 30;
    public static final int MAX_IDLE_RESUME_AFTER_SECONDS = 3600;
    public static final int MIN_RESPONSE_TIMEOUT_SECONDS = 5;
    public static final int MAX_RESPONSE_TIMEOUT_SECONDS = 300;
    public static final int MIN_MAX_INPUT_BYTES = 1;
    public static final int MAX_MAX_INPUT_BYTES = 16384;
    public static final int MIN_MAX_OUTPUT_BYTES = 1;
    public static final int MAX_MAX_OUTPUT_BYTES = 65536;
    public static final int MIN_MAX_MODEL_ITERATIONS = 1;
    public static final int MAX_MAX_MODEL_ITERATIONS = 8;
    public static final int MIN_MAX_HISTORY_BYTES = 1;
    public static final int MAX_MAX_HISTORY_BYTES = 262144;
    public static final int MIN_TRANSCRIPT_RETENTION_DAYS = 1;
    public static final int MAX_TRANSCRIPT_RETENTION_DAYS = 30;

    private InteractionProperties() {
    }

    /** section 22.2: chat scope — USER_CHAT_ONLY, or NONE (controls only). */
    public enum ContentMode { USER_CHAT_ONLY, NONE }

    /** A validated effective interaction policy. Immutable. */
    public record Policy(
            int version,
            boolean enabled,
            int idleResumeAfterSeconds,
            int responseTimeoutSeconds,
            int maxInputBytes,
            int maxOutputBytes,
            int maxModelIterations,
            int maxHistoryBytes,
            int transcriptRetentionDays,
            ContentMode contentMode) {

        public Policy {
            validateBounds(version, idleResumeAfterSeconds, responseTimeoutSeconds,
                    maxInputBytes, maxOutputBytes, maxModelIterations,
                    maxHistoryBytes, transcriptRetentionDays, contentMode);
        }
    }

    /** The section 22.2 implementation defaults. */
    public static Policy defaults() {
        return new Policy(
                VERSION, true,
                DEFAULT_IDLE_RESUME_AFTER_SECONDS,
                DEFAULT_RESPONSE_TIMEOUT_SECONDS,
                DEFAULT_MAX_INPUT_BYTES,
                DEFAULT_MAX_OUTPUT_BYTES,
                DEFAULT_MAX_MODEL_ITERATIONS,
                DEFAULT_MAX_HISTORY_BYTES,
                DEFAULT_TRANSCRIPT_RETENTION_DAYS,
                ContentMode.USER_CHAT_ONLY);
    }

    /**
     * Validate a candidate policy against the section 22.2 tighten-only
     * domain. Invalid policy fails initialization — callers translate the
     * exception into the platform's fail-closed dispatch/init behavior.
     */
    public static void validateBounds(
            int version,
            int idleResumeAfterSeconds,
            int responseTimeoutSeconds,
            int maxInputBytes,
            int maxOutputBytes,
            int maxModelIterations,
            int maxHistoryBytes,
            int transcriptRetentionDays,
            ContentMode contentMode) {
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "interaction policy version must be " + VERSION
                            + " (got " + version + ")");
        }
        requireWithin(idleResumeAfterSeconds,
                MIN_IDLE_RESUME_AFTER_SECONDS, MAX_IDLE_RESUME_AFTER_SECONDS,
                "idleResumeAfterSeconds");
        requireWithin(responseTimeoutSeconds,
                MIN_RESPONSE_TIMEOUT_SECONDS, MAX_RESPONSE_TIMEOUT_SECONDS,
                "responseTimeoutSeconds");
        requireWithin(maxInputBytes, MIN_MAX_INPUT_BYTES, MAX_MAX_INPUT_BYTES,
                "maxInputBytes");
        requireWithin(maxOutputBytes, MIN_MAX_OUTPUT_BYTES, MAX_MAX_OUTPUT_BYTES,
                "maxOutputBytes");
        requireWithin(maxModelIterations, MIN_MAX_MODEL_ITERATIONS,
                MAX_MAX_MODEL_ITERATIONS, "maxModelIterations");
        requireWithin(maxHistoryBytes, MIN_MAX_HISTORY_BYTES, MAX_MAX_HISTORY_BYTES,
                "maxHistoryBytes");
        requireWithin(transcriptRetentionDays, MIN_TRANSCRIPT_RETENTION_DAYS,
                MAX_TRANSCRIPT_RETENTION_DAYS, "transcriptRetentionDays");
        if (contentMode == null) {
            throw new IllegalArgumentException("contentMode is required");
        }
    }

    /**
     * Validate an already-constructed policy against the section 22.2
     * domain. Present for callers holding a Policy from unvalidated sources
     * (wire parsing); construction via the canonical record constructor
     * validates eagerly, so this is a defensive re-check.
     */
    public static void requireWithinBounds(Policy policy) {
        validateBounds(policy.version(), policy.idleResumeAfterSeconds(),
                policy.responseTimeoutSeconds(), policy.maxInputBytes(),
                policy.maxOutputBytes(), policy.maxModelIterations(),
                policy.maxHistoryBytes(), policy.transcriptRetentionDays(),
                policy.contentMode());
    }

    private static void requireWithin(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    field + " must be within [" + min + ".." + max
                            + "] (got " + value + ")");
        }
    }
}
