// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import java.util.Map;

/**
 * One canonical retryPolicy parser for every task type (plan Feature 10,
 * design §16.1). Step-level {@code retryPolicy} ({@code maxRetries},
 * {@code initialBackoffSeconds}, {@code maxBackoffSeconds}) supersedes the
 * legacy bare {@code maxRetries} step field; {@code WorkflowRequestService}
 * and {@code WorkflowProgressionService} both seed
 * {@code WorkflowTask.max_retries} through this class. Absent retryPolicy
 * means {@code maxRetries: 0} with default backoffs.
 */
public final class RetryPolicyParser {

    /** Default retry backoff when the policy omits it (TaskAttemptService). */
    public static final long DEFAULT_INITIAL_BACKOFF_SECONDS = 2L;
    /** Bounded maximum backoff when the policy omits it. */
    public static final long DEFAULT_MAX_BACKOFF_SECONDS = 60L;

    private RetryPolicyParser() {
    }

    /**
     * Parse the effective retryPolicy from a raw step-definition map:
     * a present {@code retryPolicy.maxRetries} wins; otherwise the legacy
     * bare {@code maxRetries}; otherwise 0.
     */
    public static int maxRetries(Map<String, Object> stepDef) {
        if (stepDef == null) {
            return 0;
        }
        Object policy = stepDef.get("retryPolicy");
        if (policy instanceof Map<?, ?> policyMap) {
            Integer fromPolicy = intValue(policyMap.get("maxRetries"));
            if (fromPolicy != null) {
                return fromPolicy;
            }
            return 0; // present retryPolicy without maxRetries means 0
        }
        return legacyMaxRetries(stepDef.get("maxRetries"));
    }

    /** Legacy bare-field parse (kept for pre-F10 step maps). */
    public static int legacyMaxRetries(Object raw) {
        Integer parsed = intValue(raw);
        return parsed == null ? 0 : parsed;
    }

    /**
     * Effective backoff window for a retry: uses retryPolicy when present,
     * bounded by {@code maxBackoffSeconds}; otherwise the TaskAttemptService
     * defaults.
     */
    public static Backoff backoff(Map<String, Object> stepDef) {
        if (stepDef != null && stepDef.get("retryPolicy") instanceof Map<?, ?> policy) {
            return new Backoff(
                    longValue(policy.get("initialBackoffSeconds"), DEFAULT_INITIAL_BACKOFF_SECONDS),
                    longValue(policy.get("maxBackoffSeconds"), DEFAULT_MAX_BACKOFF_SECONDS));
        }
        return new Backoff(DEFAULT_INITIAL_BACKOFF_SECONDS, DEFAULT_MAX_BACKOFF_SECONDS);
    }

    /** Resolved backoff window. */
    public record Backoff(long initialBackoffSeconds, long maxBackoffSeconds) {
        /**
         * Exponential delay for the given retry ordinal (0-based), bounded
         * by the max: initial × 2^retry.
         */
        public long delaySeconds(int retryOrdinal) {
            if (retryOrdinal < 0) {
                retryOrdinal = 0;
            }
            long shifted = initialBackoffSeconds << Math.min(retryOrdinal, 30);
            return Math.min(shifted, maxBackoffSeconds);
        }
    }

    private static Integer intValue(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number num) return num.intValue();
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long longValue(Object raw, long fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number num) return num.longValue();
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}