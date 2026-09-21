// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.conversation.event;

import java.util.UUID;

/**
 * Published by {@code ConversationService.closeConversation} when the
 * ACTIVE to CLOSED transition succeeded and the teardown ran (2026-09-21
 * close/archive lifecycle design section 5.2). Idempotent replays and
 * rejected closes publish nothing.
 *
 * <p>ConversationIdleListener pushes {@code session.close} to every
 * host-bound CONVERSATION session of the conversation AFTER_COMMIT. The SSE
 * {@code conversation.state} frame is the controller's push (design section
 * 5.1), so viewers receive exactly one frame per transition.</p>
 *
 * @param conversationId  the closed conversation
 * @param reason          the close reason stamped on the row (USER_ENDED)
 */
public record ConversationClosedEvent(UUID conversationId, String reason) {
}