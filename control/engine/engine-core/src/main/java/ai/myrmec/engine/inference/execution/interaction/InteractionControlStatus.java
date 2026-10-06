// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

/**
 * §3.3/§22.7 control-request disposition vocabulary: the five §22.7
 * statuses (ACCEPTED, CONFIRMATION_REQUIRED, REJECTED, DECLINED, EXPIRED)
 * plus PENDING for the awaiting-confirmation window. Column is
 * varchar(30) — enum-string persisted, no ordinal coupling.
 */
public enum InteractionControlStatus {
    /** Awaiting confirmation window handling (§3.3 "plus PENDING"). */
    PENDING,
    /** Command dispatch committed (not action completion) — §22.7. */
    ACCEPTED,
    /** Awaiting user confirmation (§22.7). */
    CONFIRMATION_REQUIRED,
    /** Engine refused the action (§22.7). */
    REJECTED,
    /** The proposing actor declined (§22.7). */
    DECLINED,
    /** The confirmation window elapsed without a decision (§22.7). */
    EXPIRED
}