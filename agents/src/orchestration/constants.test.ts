// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Cross-language UUIDv5 vector tests. The expected values are computed by
 * the Java engine's {@code OrchestrationIds} (mirrored by
 * OrchestrationIdsContractTest on the engine side) — both sides must
 * derive identical IDs from identical inputs, or correlation fails closed.
 */
import { describe, expect, test } from "vitest";
import {
  ORCHESTRATION_EVENT_NS,
  ORCHESTRATION_RESULT_NS,
  ORCHESTRATION_SCHEDULING_NS,
  WORKSPACE_ACK_NS,
  uuidV5,
} from "./constants.js";

describe("OrchestrationIds contract (§16.1)", () => {
  test("namespace constants are the fixed design values", () => {
    expect(ORCHESTRATION_RESULT_NS).toBe("ad694481-13ed-40ec-9816-645cfe953e2f");
    expect(ORCHESTRATION_EVENT_NS).toBe("97ebe21e-037e-4b3a-adb0-8b57c166e4d5");
    expect(ORCHESTRATION_SCHEDULING_NS).toBe("a5ada7e8-3b1a-4d9c-a970-ec22f1f74bd2");
    expect(WORKSPACE_ACK_NS).toBe("3c8ea127-18cb-465f-8edb-025ec79ef52a");
  });

  test("uuidV5 matches the RFC 4122 DNS-namespace example", () => {
    // Well-known vector: uuidv5(DNS namespace, "example.com").
    const id = uuidV5("6ba7b810-9dad-11d1-80b4-00c04fd430c8", "example.com");
    expect(id).toBe("cfbff0d1-9375-5685-968c-48ce8b15ae17");
  });

  test("scheduling-event shape is deterministic (run:task:episode:occurrence)", () => {
    const a = uuidV5(
      ORCHESTRATION_SCHEDULING_NS,
      "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222:1:3",
    );
    const b = uuidV5(
      ORCHESTRATION_SCHEDULING_NS,
      "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222:1:3",
    );
    expect(a).toBe(b);
    // different occurrence → different id
    const c = uuidV5(
      ORCHESTRATION_SCHEDULING_NS,
      "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222:1:4",
    );
    expect(a).not.toBe(c);
  });
});