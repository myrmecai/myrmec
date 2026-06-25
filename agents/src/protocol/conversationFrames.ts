// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Conversational-turn and HITL-approval wire frames.
 *
 * Covers the engine→agent conversation path: a `conversation.turn.assign`
 * lands when a user message hits a conversation pinned to this agent; the
 * agent streams the assistant reply back as a run of `message.delta` chunks
 * followed by a single `message.complete`. Mid-turn, a handler may emit an
 * `approval.request` and block until the matching `approval.decision` returns.
 *
 * Inbound frames are validated with zod at the boundary; outbound frames are
 * built with helpers that stamp the engine's camelCase field names so the
 * agent and engine speak one protocol.
 */
import { z } from "zod";
import { MessageType } from "./messages.js";
import { makeEnvelope, type Envelope } from "./envelope.js";
import { modelInfoSchema } from "./taskFrames.js";

// ==================== Inbound payload schemas ====================

/** One prior message in the sliding-window context. `role` is the engine's
 * `ConversationMessage.Role` enum string (USER / ASSISTANT / SYSTEM). */
export const conversationHistoryEntrySchema = z.object({
  role: z.string(),
  content: z.string(),
  sequenceNo: z.number(),
});
export type ConversationHistoryEntryWire = z.infer<
  typeof conversationHistoryEntrySchema
>;

/** One attachment bound to the triggering user message (#103). Small text
 * documents arrive with `inlineText` populated; binaries and large files
 * carry metadata only and are fetched on demand. `image` is true only when
 * the file is an image AND the resolved model supports vision. */
export const conversationAttachmentSchema = z.object({
  id: z.string(),
  filename: z.string(),
  mediaType: z.string(),
  sizeBytes: z.number(),
  sha256: z.string().nullish(),
  image: z.boolean().default(false),
  inlineText: z.string().nullish(),
  /** True when inline extraction was skipped because the single file exceeded
   * the per-attachment token cap; the agent reads it on demand instead. */
  inlineTextOmittedBySize: z.boolean().default(false),
  /** True when inline extraction was dropped because inlining it would breach
   * the turn's aggregate inline-context budget (#103 Slice B). */
  inlineTextOmittedByBudget: z.boolean().default(false),
  /** Engine path to fetch the raw bytes on demand (image inlining + large/
   * binary read-on-demand). Absent for fully-inlined text. */
  readContentPath: z.string().nullish(),
});
export type ConversationAttachmentWire = z.infer<
  typeof conversationAttachmentSchema
>;

/** `conversation.turn.assign` payload — everything needed to run one
 * assistant turn in a conversational session. */
export const conversationTurnAssignPayloadSchema = z.object({
  conversationId: z.string(),
  projectId: z.string(),
  agentId: z.string(),
  assistantSequenceNo: z.number().int().nonnegative(),
  systemPrompt: z.string().nullish(),
  pinnedFacts: z.string().nullish(),
  history: z.array(conversationHistoryEntrySchema).default([]),
  userMessage: z.string(),
  timeoutSeconds: z.number().default(300),
  model: modelInfoSchema.nullish(),
  attachments: z.array(conversationAttachmentSchema).default([]),
});
export type ConversationTurnAssignPayload = z.infer<
  typeof conversationTurnAssignPayloadSchema
>;

/** `approval.decision` payload (Engine → Agent). Echoes `clientRequestId`
 * from the originating request so the coordinator can resolve the right
 * pending wait. `decision` is APPROVED / REJECTED / EXPIRED. */
export const approvalDecisionPayloadSchema = z.object({
  conversationId: z.string(),
  clientRequestId: z.string().nullish(),
  requestMessageId: z.string().nullish(),
  decision: z.string(),
  comment: z.string().nullish(),
  responseMessageId: z.string().nullish(),
  approverUserId: z.string().nullish(),
});
export type ApprovalDecisionWire = z.infer<typeof approvalDecisionPayloadSchema>;

/** `conversation.turn.cancel` payload (Engine → Agent). Tells the bound
 * worker to abort the in-flight assistant turn for this conversation. */
export const conversationTurnCancelPayloadSchema = z.object({
  conversationId: z.string(),
  reason: z.string().nullish(),
});
export type ConversationTurnCancelPayload = z.infer<
  typeof conversationTurnCancelPayloadSchema
>;

// ==================== Outbound frame builders ====================

/** `conversation.attach` — the first frame the Supervisor sends on a freshly
 * opened conversation socket. The agent JWT travels in the handshake query;
 * the body just names which agent instance is attaching to which conversation
 * so the engine can pin the conversation home node and flip the agent to
 * BOUND (agent-concurrency §9.8). */
export function conversationAttach(detail: {
  agentId: string;
  conversationId: string;
}): Envelope {
  return makeEnvelope(MessageType.CONVERSATION_ATTACH, {
    agentId: detail.agentId,
    conversationId: detail.conversationId,
  });
}

/** `message.delta` — one chunk of a streamed assistant turn. `deltaIndex` is
 * the 0-based per-turn counter the broker uses to drop out-of-order frames. */
export function messageDelta(detail: {
  conversationId: string;
  sequenceNo: number;
  deltaIndex: number;
  content: string;
}): Envelope {
  return makeEnvelope(MessageType.MESSAGE_DELTA, {
    conversationId: detail.conversationId,
    sequenceNo: detail.sequenceNo,
    deltaIndex: detail.deltaIndex,
    content: detail.content,
  });
}

/** `message.complete` — terminal marker carrying the full canonical text so
 * the engine can persist the row even for a late-reconnecting viewer. */
export function messageComplete(detail: {
  conversationId: string;
  sequenceNo: number;
  content: string;
  modelCode?: string;
  tokenCount?: number;
}): Envelope {
  return makeEnvelope(MessageType.MESSAGE_COMPLETE, {
    conversationId: detail.conversationId,
    sequenceNo: detail.sequenceNo,
    content: detail.content,
    ...(detail.modelCode !== undefined ? { modelCode: detail.modelCode } : {}),
    ...(detail.tokenCount !== undefined
      ? { tokenCount: detail.tokenCount }
      : {}),
  });
}

/** `task.cancelled` — agent acknowledgement of a turn cancel. Carries any
 * partial text streamed before the abort took effect so the engine can
 * persist it for transcripts. `sequenceNo` is the in-flight assistant turn's
 * pre-allocated sequence number. */
export function taskCancelled(detail: {
  conversationId: string;
  sequenceNo: number;
  partialContent?: string;
  reason?: string;
}): Envelope {
  return makeEnvelope(MessageType.TASK_CANCELLED, {
    conversationId: detail.conversationId,
    sequenceNo: detail.sequenceNo,
    ...(detail.partialContent !== undefined
      ? { partialContent: detail.partialContent }
      : {}),
    reason: detail.reason ?? "user_request",
  });
}

/** `approval.request` — agent asks a human to approve a proposed action. The
 * agent generates `clientRequestId`; the engine echoes it on the decision. */
export function approvalRequest(detail: {
  conversationId: string;
  clientRequestId: string;
  content?: string;
  payloadJson?: string;
  expiresAt?: string;
}): Envelope {
  return makeEnvelope(MessageType.APPROVAL_REQUEST, {
    conversationId: detail.conversationId,
    clientRequestId: detail.clientRequestId,
    ...(detail.content !== undefined ? { content: detail.content } : {}),
    ...(detail.payloadJson !== undefined
      ? { payloadJson: detail.payloadJson }
      : {}),
    ...(detail.expiresAt !== undefined ? { expiresAt: detail.expiresAt } : {}),
  });
}
