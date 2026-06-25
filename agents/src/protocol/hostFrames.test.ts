// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect } from "vitest";
import {
  agentProvisionsSchema,
  reportedCapacitySchema,
  hostAnnouncePayloadSchema,
  hostAnnounce,
} from "./hostFrames.js";
import { MessageType } from "./messages.js";

describe("hostFrames schemas", () => {
  it("defaults provisions buckets to empty arrays", () => {
    const prov = agentProvisionsSchema.parse({});
    expect(prov.tools).toEqual([]);
    expect(prov.runtime).toEqual([]);
  });

  it("parses provisions with tools and runtime", () => {
    const prov = agentProvisionsSchema.parse({
      tools: ["sql", "search"],
      runtime: ["python3.11", "node20"],
    });
    expect(prov.tools).toEqual(["sql", "search"]);
    expect(prov.runtime).toEqual(["python3.11", "node20"]);
  });

  it("requires numeric capacity fields", () => {
    expect(reportedCapacitySchema.safeParse({ cpuCount: 8 }).success).toBe(
      false,
    );
    const cap = reportedCapacitySchema.parse({
      cpuCount: 8,
      totalMemoryBytes: 16_000_000_000,
    });
    expect(cap.cpuCount).toBe(8);
  });

  it("parses a full host.announce payload", () => {
    const wire = hostAnnouncePayloadSchema.parse({
      provisions: { tools: ["sql"], runtime: [] },
      reportedCapacity: { cpuCount: 4, totalMemoryBytes: 8_000_000_000 },
    });
    expect(wire.provisions.tools).toEqual(["sql"]);
    expect(wire.reportedCapacity.cpuCount).toBe(4);
  });
});

describe("hostAnnounce builder", () => {
  it("builds a host.announce envelope", () => {
    const env = hostAnnounce(
      { tools: ["sql"], runtime: ["node20"] },
      { cpuCount: 4, totalMemoryBytes: 8_000_000_000 },
    );
    expect(env.type).toBe(MessageType.HOST_ANNOUNCE);
    expect(env.payload).toEqual({
      provisions: { tools: ["sql"], runtime: ["node20"] },
      reportedCapacity: { cpuCount: 4, totalMemoryBytes: 8_000_000_000 },
    });
    expect(typeof env.timestamp).toBe("string");
  });
});
