// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

/**
 * §3.2 interaction state machine: ACCEPTED (admitted, pending on the
 * execution) → RUNNING (host working) → COMPLETED | FAILED (final,
 * with usage attribution).
 */
public enum InteractionStatus { ACCEPTED, RUNNING, COMPLETED, FAILED }