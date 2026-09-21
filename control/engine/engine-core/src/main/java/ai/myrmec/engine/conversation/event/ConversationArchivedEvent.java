// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.conversation.event;

import java.util.UUID;

/**
 * Published by {@code ConversationService.archiveConversation} when the
 * archive transition tears the conversation's sessions down (ACTIVE to
 * ARCHIVED). Archiving an already-CLOSED row runs no teardown and publishes
 * nothing - its sessions were pushed at close time.
 *
 * @param conversationId  the archived conversation
 * @param reason          the archive reason stamped on the row (USER_ARCHIVED)
 */
public record ConversationArchivedEvent(UUID conversationId, String reason) {
}