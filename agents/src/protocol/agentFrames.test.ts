// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect } from "vitest";
import {
  agentBindPayloadSchema,
  agentReleasePayloadSchema,
  agentBindAck,
  agentBindNack,
} from "./agentFrames.js";
import { MessageType } from "./messages.js";

describe("agentFrames", () => {
  describe("agent.bind", () => {
    it("parses a conversation + profile-version binding", () => {
      const parsed = agentBindPayloadSchema.parse({
        conversationId: "c1",
        profileVersionId: "pv1",
      });
      expect(parsed).toEqual({ conversationId: "c1", profileVersionId: "pv1" });
    });

    it("rejects a binding missing the profile version", () => {
      expect(
        agentBindPayloadSchema.safeParse({ conversationId: "c1" }).success,
      ).toBe(false);
    });

    it("parses the home-node address when present", () => {
      const parsed = agentBindPayloadSchema.parse({
        conversationId: "c1",
        profileVersionId: "pv1",
        homeNodeId: "node-a",
        homeNodeAddr: "10.0.0.5:8080",
      });
      expect(parsed.homeNodeId).toBe("node-a");
      expect(parsed.homeNodeAddr).toBe("10.0.0.5:8080");
    });
  });

  describe("agent.release", () => {
    it("parses a release with an optional reason", () => {
      const parsed = agentReleasePayloadSchema.parse({
        conversationId: "c1",
        reason: "turn complete",
      });
      expect(parsed).toEqual({ conversationId: "c1", reason: "turn complete" });
    });

    it("parses a release without a reason", () => {
      const parsed = agentReleasePayloadSchema.parse({ conversationId: "c1" });
      expect(parsed.reason).toBeUndefined();
    });

    it("rejects a release missing the conversation id", () => {
      expect(agentReleasePayloadSchema.safeParse({}).success).toBe(false);
    });
  });

  describe("agent.bind.ack / agent.bind.nack builders", () => {
    it("builds an ack envelope carrying the conversation id", () => {
      const env = agentBindAck({ conversationId: "c1" });
      expect(env.type).toBe(MessageType.AGENT_BIND_ACK);
      expect(env.payload).toEqual({ conversationId: "c1" });
    });

    it("builds a nack envelope with an optional reason", () => {
      const env = agentBindNack({ conversationId: "c1", reason: "dial failed" });
      expect(env.type).toBe(MessageType.AGENT_BIND_NACK);
      expect(env.payload).toEqual({ conversationId: "c1", reason: "dial failed" });
    });

    it("omits the reason when not provided", () => {
      const env = agentBindNack({ conversationId: "c1" });
      expect(env.payload).toEqual({ conversationId: "c1" });
    });
  });
});
