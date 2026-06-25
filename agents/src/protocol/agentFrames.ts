// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Reserve-time binding frames for the Engine → Agent protocol (Slice 3).
 *
 * After the engine atomically reserves a warm worker for a conversation it
 * sends `agent.bind`, telling the worker which conversation it now serves and
 * which profile version to load (agent-concurrency §9.5). `agent.release`
 * tears that binding down and returns the worker to the warm pool. Both are
 * inbound to the SDK, so this module exposes parse schemas + types only — the
 * engine is the sender.
 *
 * The bind names the home engine node the Agent must dial: `homeNodeAddr` is
 * the direct (in-cluster) pod address the Supervisor opens its dedicated
 * conversation socket to (agent-concurrency §9.4/§9.9). Both home-node fields
 * are optional so a single-node deployment can omit them — the Supervisor
 * falls back to its control-socket engine URL.
 */
import { z } from "zod";
import { MessageType } from "./messages.js";
import { makeEnvelope, type Envelope } from "./envelope.js";

/** `agent.bind` payload (Engine → Agent). */
export const agentBindPayloadSchema = z.object({
  conversationId: z.string(),
  profileVersionId: z.string(),
  /** Stable id of the home engine node holding the conversation socket. */
  homeNodeId: z.string().nullish(),
  /** Direct pod address (host:port) the Supervisor dials for the
   * conversation socket. Absent in single-node mode. */
  homeNodeAddr: z.string().nullish(),
});
export type AgentBindWire = z.infer<typeof agentBindPayloadSchema>;

/** `agent.release` payload (Engine → Agent). */
export const agentReleasePayloadSchema = z.object({
  conversationId: z.string(),
  reason: z.string().optional(),
});
export type AgentReleaseWire = z.infer<typeof agentReleasePayloadSchema>;

/**
 * `agent.bind.ack` (Host → Engine). Sent on the control socket once the
 * Supervisor has opened the dedicated conversation socket for a bound
 * conversation. The engine advances the reserved worker to `CONNECTING`
 * (agent-concurrency §9.5). The `conversationId` echoes the bind so the
 * engine can match the handshake to the right worker.
 */
export function agentBindAck(detail: { conversationId: string }): Envelope {
  return makeEnvelope(MessageType.AGENT_BIND_ACK, {
    conversationId: detail.conversationId,
  });
}

/**
 * `agent.bind.nack` (Host → Engine). Sent on the control socket when the
 * Supervisor cannot serve a bind (dialing the home node / opening the
 * conversation socket failed). The engine releases the reserved worker back
 * to `IDLE` for re-dispatch (agent-concurrency §9.5). `reason` is optional
 * diagnostic text.
 */
export function agentBindNack(detail: {
  conversationId: string;
  reason?: string;
}): Envelope {
  return makeEnvelope(MessageType.AGENT_BIND_NACK, {
    conversationId: detail.conversationId,
    ...(detail.reason !== undefined ? { reason: detail.reason } : {}),
  });
}
