// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

/**
 * §3.4 outbox delivery state: PENDING (awaiting dispatch or retransmit),
 * ACKED (durable SDK handling recorded — not action completion), EXPIRED
 * (terminal execution or lifetime bound reached).
 */
public enum OutboxStatus { PENDING, ACKED, EXPIRED }