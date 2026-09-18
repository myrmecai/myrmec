// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The wire envelope. Every frame on the control socket is
 * `{ type, timestamp, payload }` (matches the Python `WebSocketMessage`).
 * `Envelope` is a loosely-typed frame shape: payloads are intentionally
 * `unknown` and narrowed by the consuming handler — the envelope layer only
 * guarantees the frame shape, not payload semantics. The `type` is a plain
 * string: under the unified wire the worker's outbound frames carry unified
 * frame-family names ("execution.delta", "session.open", …) while the
 * orchestrator's legacy-shape frames (orchestration.event / result /
 * approval_requested, the outbox records) keep their own families.
 */

import { z } from "zod";

/** Raw frame shape as it appears on the wire. */
export const envelopeSchema = z.object({
  type: z.string(),
  timestamp: z.string(), // ISO-8601 UTC (Python emits `datetime` as ISO string)
  payload: z.unknown(),
});

/** A validated, untyped-payload envelope as decoded from the socket. */
export type RawEnvelope = z.infer<typeof envelopeSchema>;

/** A typed envelope frame: a frame-family string carrying a `P` payload. */
export interface Envelope<T extends string = string, P = unknown> {
  type: T;
  timestamp: string;
  payload: P;
}

/**
 * Build an outbound envelope. Mirrors `WebSocketMessage.create` — stamps a
 * fresh UTC ISO timestamp.
 */
export function makeEnvelope<T extends string, P>(
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
