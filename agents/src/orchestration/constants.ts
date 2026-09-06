// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createHash } from "node:crypto";

/**
 * The four deterministic UUIDv5 namespaces and derivation sites (design
 * §16.1). Mirrored VERBATIM from the Java engine's
 * {@code ai.myrmec.engine.workflow.OrchestrationIds} — the two languages
 * must never drift; any change is a wire-breaking change made on both
 * sides together.
 *
 * Each namespace hashes a different name shape: result digest, dispatch
 * sequence, run/task/episode, release/generation. The derivation inputs —
 * field selection, order, and the ":" separator — are part of the
 * contract at each site; changing a field order is as breaking as
 * changing the namespace.
 */

export const ORCHESTRATION_RESULT_NS = "ad694481-13ed-40ec-9816-645cfe953e2f";
export const ORCHESTRATION_EVENT_NS = "97ebe21e-037e-4b3a-adb0-8b57c166e4d5";
export const ORCHESTRATION_SCHEDULING_NS = "a5ada7e8-3b1a-4d9c-a970-ec22f1f74bd2";
export const WORKSPACE_ACK_NS = "3c8ea127-18cb-465f-8edb-025ec79ef52a";

/**
 * UUIDv5 (SHA-1, RFC 4122) name-based derivation — the deterministic ID
 * basis for results, events, scheduling records, and release
 * acknowledgements. Produces the exact same UUID as the Java side for
 * identical namespace + name inputs.
 */
export function uuidV5(namespace: string, name: string): string {
  const ns = parseUuidBytes(namespace);
  const nameBytes = Buffer.from(name, "utf-8");
  const input = Buffer.concat([ns, nameBytes]);
  const digest = createHash("sha1").update(input).digest();

  // RFC 4122 §4.3: version 5, variant RFC 4122
  digest[6] = ((digest[6] as number) & 0x0f) | 0x50;
  digest[8] = ((digest[8] as number) & 0x3f) | 0x80;

  const hex = digest.subarray(0, 16).toString("hex");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join("-");
}

function parseUuidBytes(uuid: string): Buffer {
  const hex = uuid.replace(/-/g, "");
  const bytes = Buffer.alloc(16);
  for (let i = 0; i < 16; i++) {
    bytes[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}