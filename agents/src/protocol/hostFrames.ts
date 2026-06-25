// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Agent Host control frames for the Engine ↔ Agent protocol.
 *
 * `host.announce` is the Supervisor (Agent Host) advertising what it can run:
 * its installed `provisions` (tools + runtime — the supply side of reserve-time
 * capability matching, agent-host-model §3.2/§4.3) and the `reportedCapacity`
 * it auto-sized its warm pool from (§4.4). The host is the source of truth, so
 * it re-asserts this on every control-socket (re)connect and the engine
 * overwrites the AgentHost row each time.
 */
import { z } from "zod";
import { MessageType } from "./messages.js";
import { makeEnvelope, type Envelope } from "./envelope.js";

/** Tools + runtime a host advertises as installed (the supply side of matching). */
export const agentProvisionsSchema = z.object({
  tools: z.array(z.string()).default([]),
  runtime: z.array(z.string()).default([]),
});
export type AgentProvisions = z.infer<typeof agentProvisionsSchema>;

/** CPU/RAM the Supervisor auto-sized its warm pool from. */
export const reportedCapacitySchema = z.object({
  cpuCount: z.number(),
  totalMemoryBytes: z.number(),
});
export type ReportedCapacity = z.infer<typeof reportedCapacitySchema>;

/** `host.announce` payload (Agent Host → Engine). */
export const hostAnnouncePayloadSchema = z.object({
  provisions: agentProvisionsSchema,
  reportedCapacity: reportedCapacitySchema,
});
export type HostAnnouncePayload = z.infer<typeof hostAnnouncePayloadSchema>;

/** `host.announce` — the Supervisor advertises its provisions + capacity. */
export function hostAnnounce(
  provisions: AgentProvisions,
  reportedCapacity: ReportedCapacity,
): Envelope {
  return makeEnvelope(MessageType.HOST_ANNOUNCE, { provisions, reportedCapacity });
}
