// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import java.time.Instant;
import java.util.UUID;

/**
 * §3.2 fixed record — the service-layer admission return shape (Tasks 6/8
 * consume this; the plan's example boundary, verbatim).
 *
 * @param interactionId    the execution_interactions.id (the §22.6 wire id)
 * @param ordinal          the admitted serial chat ordinal
 * @param status           the admission status (ACCEPTED on success)
 * @param responseDeadline the §3.2 settle-by instant
 */
public record InteractionAdmission(UUID interactionId, long ordinal,
                                   InteractionStatus status, Instant responseDeadline) {}