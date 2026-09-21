// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.conversation.event;

import java.util.UUID;

/**
 * Published by the session-allocation idle-lease sweep
 * ({@code SessionAllocator.expireIdleLeases}) after it closes an idle
 * CONVERSATION session (2026-09-21 close/archive lifecycle design section
 * 5.3).
 *
 * <p>The sweep cannot depend on the host-control handler or the stream
 * broker (the handler injects ConversationService, which transitively
 * reaches the allocation layer - a bean cycle), so this event decouples
 * the allocation sweep from the push side. ConversationIdleListener picks
 * it up AFTER_COMMIT and fans the notification out to the serving host and
 * the conversation's SSE viewers.</p>
 *
 * @param sessionId       the session row the sweep just closed
 * @param conversationId  the conversation the session served (the row's refId)
 * @param hostInstanceId  the host instance that served the session; never
 *                        null (publication is skipped for sessions without one)
 */
public record IdleSessionExpiredEvent(UUID sessionId, UUID conversationId, UUID hostInstanceId) {
}