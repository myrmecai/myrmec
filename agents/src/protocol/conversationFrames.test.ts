// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect } from "vitest";
import {
  conversationTurnAssignPayloadSchema,
  approvalDecisionPayloadSchema,
  conversationAttach,
  messageDelta,
  messageComplete,
  approvalRequest,
} from "./conversationFrames.js";
import { MessageType } from "./messages.js";

describe("conversation frame schemas", () => {
  it("parses a conversation.turn.assign with defaults", () => {
    const parsed = conversationTurnAssignPayloadSchema.parse({
      conversationId: "c1",
      projectId: "p1",
      agentId: "a1",
      assistantSequenceNo: 7,
      userMessage: "hello",
    });
    expect(parsed.history).toEqual([]);
    expect(parsed.timeoutSeconds).toBe(300);
    expect(parsed.systemPrompt).toBeUndefined();
    expect(parsed.model).toBeUndefined();
  });

  it("parses history entries and model info", () => {
    const parsed = conversationTurnAssignPayloadSchema.parse({
      conversationId: "c1",
      projectId: "p1",
      agentId: "a1",
      assistantSequenceNo: 0,
      userMessage: "hi",
      history: [{ role: "USER", content: "earlier", sequenceNo: 1 }],
      model: { provider: "openai", modelId: "gpt-4o" },
    });
    expect(parsed.history[0]).toMatchObject({ role: "USER", sequenceNo: 1 });
    expect(parsed.model?.modelId).toBe("gpt-4o");
  });

  it("rejects a negative assistant sequence number", () => {
    expect(() =>
      conversationTurnAssignPayloadSchema.parse({
        conversationId: "c1",
        projectId: "p1",
        agentId: "a1",
        assistantSequenceNo: -1,
        userMessage: "hi",
      }),
    ).toThrow();
  });

  it("parses an approval.decision", () => {
    const parsed = approvalDecisionPayloadSchema.parse({
      conversationId: "c1",
      clientRequestId: "r1",
      decision: "APPROVED",
      comment: "ok",
    });
    expect(parsed.decision).toBe("APPROVED");
    expect(parsed.clientRequestId).toBe("r1");
  });
});

describe("conversation frame builders", () => {
  it("builds a conversation.attach", () => {
    const frame = conversationAttach({ agentId: "a1", conversationId: "c1" });
    expect(frame.type).toBe(MessageType.CONVERSATION_ATTACH);
    expect(frame.payload).toEqual({ agentId: "a1", conversationId: "c1" });
    expect(typeof frame.timestamp).toBe("string");
  });

  it("builds a message.delta", () => {
    const frame = messageDelta({
      conversationId: "c1",
      sequenceNo: 3,
      deltaIndex: 0,
      content: "Hi",
    });
    expect(frame.type).toBe(MessageType.MESSAGE_DELTA);
    expect(frame.payload).toEqual({
      conversationId: "c1",
      sequenceNo: 3,
      deltaIndex: 0,
      content: "Hi",
    });
    expect(typeof frame.timestamp).toBe("string");
  });

  it("builds a message.complete and omits absent optionals", () => {
    const frame = messageComplete({
      conversationId: "c1",
      sequenceNo: 3,
      content: "Hi there",
    });
    expect(frame.type).toBe(MessageType.MESSAGE_COMPLETE);
    expect(frame.payload).toEqual({
      conversationId: "c1",
      sequenceNo: 3,
      content: "Hi there",
    });
  });

  it("includes modelCode and tokenCount when supplied", () => {
    const frame = messageComplete({
      conversationId: "c1",
      sequenceNo: 3,
      content: "Hi",
      modelCode: "openai/gpt-4o",
      tokenCount: 12,
    });
    expect(frame.payload).toMatchObject({
      modelCode: "openai/gpt-4o",
      tokenCount: 12,
    });
  });

  it("builds an approval.request", () => {
    const frame = approvalRequest({
      conversationId: "c1",
      clientRequestId: "r1",
      content: "Delete file?",
      payloadJson: '{"clientRequestId":"r1"}',
    });
    expect(frame.type).toBe(MessageType.APPROVAL_REQUEST);
    expect(frame.payload).toEqual({
      conversationId: "c1",
      clientRequestId: "r1",
      content: "Delete file?",
      payloadJson: '{"clientRequestId":"r1"}',
    });
  });
});
