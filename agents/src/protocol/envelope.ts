// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The wire envelope. Every frame on the control socket is
 * `{ type, timestamp, payload }` (matches the Python `WebSocketMessage`).
 * `Envelope` is a discriminated union on `type` (§9.6.6); payloads are
 * intentionally `unknown` here and narrowed by the consuming handler — the
 * envelope layer only guarantees the frame shape, not payload semantics.
 */

import { z } from "zod";
import { MessageType } from "./messages.js";

/** Raw frame shape as it appears on the wire. */
export const envelopeSchema = z.object({
  type: z.string(),
  timestamp: z.string(), // ISO-8601 UTC (Python emits `datetime` as ISO string)
  payload: z.unknown(),
});

/** A validated, untyped-payload envelope as decoded from the socket. */
export type RawEnvelope = z.infer<typeof envelopeSchema>;

/** A typed envelope frame: a known `type` carrying a `P` payload. */
export interface Envelope<T extends MessageType = MessageType, P = unknown> {
  type: T;
  timestamp: string;
  payload: P;
}

/**
 * Build an outbound envelope. Mirrors `WebSocketMessage.create` — stamps a
 * fresh UTC ISO timestamp.
 */
export function makeEnvelope<T extends MessageType, P>(
  type: T,
  payload: P,
): Envelope<T, P> {
  return { type, timestamp: new Date().toISOString(), payload };
}

/** Serialize an envelope for the socket. */
export function encodeEnvelope(envelope: Envelope): string {
  return JSON.stringify(envelope);
}

/**
 * Validate-at-the-boundary decode (REQ-A-074 hygiene): parse JSON, assert the
 * frame shape, and hand back a `RawEnvelope`. Throws on malformed frames so a
 * bad frame never silently flows into a handler.
 */
export function decodeEnvelope(raw: string): RawEnvelope {
  return envelopeSchema.parse(JSON.parse(raw));
}
