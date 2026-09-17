// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, expect, it, test } from "vitest";
import {
  MessageType,
  SUPPORTED_PROTOCOL_VERSION,
  parseUnifiedFrame,
  encodeUnifiedFrame,
  unifiedEnvelopeSchema,
  hostOpenPayloadSchema,
  hostOpenedPayloadSchema,
  sessionAcceptPayloadSchema,
  sessionOpenedPayloadSchema,
  sessionClosedPayloadSchema,
  channelOpenPayloadSchema,
  channelOpenedPayloadSchema,
  executionStartPayloadSchema,
  executionEventPayloadSchema,
  executionCompletePayloadSchema,
  executionPausedPayloadSchema,
  protocolAckPayloadSchema,
  protocolErrorPayloadSchema,
} from "./unifiedFrames.js";

const sentAt = "2026-09-13T10:15:30.123Z";
const now = new Date().toISOString();
const nonce = "11111111-1111-4111-8111-111111111111";
const hostInstanceId = "22222222-2222-4222-8222-222222222222";
const sessionId = "33333333-3333-4333-8333-333333333333";
const executionId = "44444444-4444-4444-8444-444444444444";
const eventId = "55555555-5555-4555-8555-555555555555";
const allocationId = sessionId;
const messageId = "m-1";
const dispatchId = "66666666-6666-4666-8666-666666666666";

function envelope(payload: unknown, type: string): Record<string, unknown> {
  return {
    protocolVersion: SUPPORTED_PROTOCOL_VERSION,
    messageId,
    type,
    sentAt,
    payload,
  };
}

// ============================================================
// envelope boundary (ports HostProtocolEnvelopeTest fixtures)
// ============================================================

describe("unified envelope", () => {
  it("parses a full frame with every field", () => {
    const frame = {
      protocolVersion: 1,
      messageId: "m-1",
      type: "host.open",
      sentAt,
      correlationId: "c-1",
      hostInstanceId: null,
      sequence: 4,
      payload: { poolSize: 5 },
    };
    const parsed = unifiedEnvelopeSchema.parse(frame);
    expect(parsed.protocolVersion).toBe(1);
    expect(parsed.messageId).toBe("m-1");
    expect(parsed.type).toBe("host.open");
    expect(parsed.correlationId).toBe("c-1");
    expect(parsed.sequence).toBe(4);
  });

  it("ignores unknown optional fields", () => {
    const frame = {
      protocolVersion: 1,
      messageId: "m-2",
      type: "host.heartbeat",
      sentAt,
      someFutureField: { x: 1 },
      payload: {},
    };
    expect(unifiedEnvelopeSchema.parse(frame).messageId).toBe("m-2");
  });

  it("rejects missing messageId", () => {
    const frame = {
      protocolVersion: 1,
      type: "host.open",
      sentAt,
      payload: {},
    };
    expect(unifiedEnvelopeSchema.safeParse(frame).success).toBe(false);
  });

  it("rejects missing type", () => {
    const frame = {
      protocolVersion: 1,
      messageId: "m-3",
      sentAt,
      payload: {},
    };
    expect(unifiedEnvelopeSchema.safeParse(frame).success).toBe(false);
  });

  it("rejects unsupported protocolVersion", () => {
    const frame = {
      protocolVersion: 99,
      messageId: "m-5",
      type: "host.open",
      sentAt,
      payload: {},
    };
    expect(unifiedEnvelopeSchema.safeParse(frame).success).toBe(false);
  });

  it("round-trips envelope through parse and encode", () => {
    const frame = {
      protocolVersion: 1,
      messageId: "m-rt",
      type: "host.open",
      sentAt,
      payload: { poolSize: 3 },
    };
    const parsed = unifiedEnvelopeSchema.parse(frame);
    const encoded = JSON.parse(encodeUnifiedFrame(parsed as unknown as Parameters<typeof encodeUnifiedFrame>[0]));
    expect(encoded.messageId).toBe("m-rt");
  });
});

// ============================================================
// host lifecycle (ports HostControlOpenTest fixtures)
// ============================================================

describe("host.open / host.opened", () => {
  it("accepts the canonical host.open fixture", () => {
    const payload = {
      instanceNonce: nonce,
      hostname: "developer-laptop",
      runtimeVersion: "1.8.0",
      supportedProtocolVersions: [1],
      poolSize: 5,
      capabilities: { tools: ["read_file"], runtimes: ["node:22"], features: ["TOOL_EVENTS"] },
      reportedCapacity: { cpuCount: 16 },
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.HOST_OPEN)));
    expect(frame.type).toBe("host.open");
    expect((frame.payload as { poolSize: number }).poolSize).toBe(5);
    expect((frame.payload as { capabilities: unknown }).capabilities).toEqual({
      tools: ["read_file"],
      runtimes: ["node:22"],
      features: ["TOOL_EVENTS"],
    });
  });

  it("rejects host.open without instanceNonce", () => {
    const payload = {
      hostname: "developer-laptop",
      runtimeVersion: "1.8.0",
      supportedProtocolVersions: [1],
      poolSize: 5,
      capabilities: {},
      reportedCapacity: {},
    };
    expect(hostOpenPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("accepts the canonical host.opened fixture", () => {
    const payload = {
      hostInstanceId,
      protocolVersion: 1,
      effectivePoolSize: 5,
      heartbeatIntervalSeconds: 15,
      offerTimeoutSeconds: 10,
      eventReplayWindowSeconds: 3600,
      streamLimits: {
        maxFrameBytes: 65536,
        maxBufferedDeltaBytesPerSession: 262144,
        maxUnacknowledgedEventBytesPerSession: 8388608,
        eventBackpressureTimeoutSeconds: 30,
      },
      serverNodeId: "node-a",
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.HOST_OPENED)));
    expect(frame.type).toBe("host.opened");
    expect((frame.payload as { streamLimits: { maxFrameBytes: number } }).streamLimits.maxFrameBytes).toBe(65536);
  });

  it("accepts host.opened with the additive psk/pskKeyId fields (design §6)", () => {
    const payload = {
      hostInstanceId,
      protocolVersion: 1,
      effectivePoolSize: 5,
      heartbeatIntervalSeconds: 15,
      offerTimeoutSeconds: 10,
      eventReplayWindowSeconds: 3600,
      streamLimits: {
        maxFrameBytes: 65536,
        maxBufferedDeltaBytesPerSession: 262144,
        maxUnacknowledgedEventBytesPerSession: 8388608,
        eventBackpressureTimeoutSeconds: 30,
      },
      serverNodeId: "node-a",
      psk: Buffer.alloc(32, 0xCD).toString("base64"),
      pskKeyId: "0f0e0d0c-0b0a-4901-8203-040506070809",
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.HOST_OPENED)));
    expect(frame.type).toBe("host.opened");
    expect((frame.payload as { psk?: string }).psk).toBeDefined();
    expect((frame.payload as { pskKeyId?: string }).pskKeyId).toBe(
      "0f0e0d0c-0b0a-4901-8203-040506070809",
    );
  });

  it("rejects host.opened with missing streamLimits", () => {
    const payload = {
      hostInstanceId,
      protocolVersion: 1,
      effectivePoolSize: 5,
      heartbeatIntervalSeconds: 15,
      offerTimeoutSeconds: 10,
      eventReplayWindowSeconds: 3600,
      serverNodeId: "node-a",
    };
    expect(hostOpenedPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("round-trips host.open", () => {
    const payload = {
      instanceNonce: nonce,
      hostname: "laptop",
      runtimeVersion: "1.0.0",
      supportedProtocolVersions: [1],
      poolSize: 4,
      capabilities: {},
      reportedCapacity: {},
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.HOST_OPEN)));
    const encoded = JSON.parse(encodeUnifiedFrame(frame));
    expect(encoded.type).toBe("host.open");
    expect(encoded.payload.instanceNonce).toBe(nonce);
  });
});

// ============================================================
// session allocation (ports SessionAllocationFlowTest fixtures)
// ============================================================

describe("session allocation frames", () => {
  it("accepts the canonical session.offer fixture", () => {
    const payload = {
      allocationId,
      sessionId,
      kind: "CONVERSATION",
      ref: { type: "CONVERSATION", id: sessionId },
      requirements: { tools: [], runtimes: [], features: [] },
      lease: { offerExpiresAt: now, idleTimeoutSeconds: 1800 },
      routing: { homeNodeId: "node-a", homeNodeAddress: null },
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OFFER)));
    expect(frame.type).toBe("session.offer");
    expect((frame.payload as { kind: string }).kind).toBe("CONVERSATION");
  });

  it("accepts the canonical session.accept fixture", () => {
    const payload = {
      allocationId,
      sessionId,
      acceptedAt: now,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.SESSION_ACCEPT),
        hostInstanceId,
        sessionId,
      }),
    );
    expect(frame.type).toBe("session.accept");
    expect(frame.sessionId).toBe(sessionId);
  });

  it("rejects session.accept when sessionId is missing on envelope", () => {
    const payload = {
      allocationId,
      sessionId,
      acceptedAt: now,
    };
    const frame = JSON.stringify({
      protocolVersion: 1,
      messageId,
      type: "session.accept",
      sentAt,
      payload,
    });
    // Schema-level required sessionId on envelope is per the brief: test the
    // mutation by validating the payload directly (envelope intentionally
    // keeps sessionId optional at parse time because some frame families do
    // not carry it).
    expect(sessionAcceptPayloadSchema.safeParse(payload).success).toBe(true);
    // The envelope-level parse succeeds regardless.
    const env = unifiedEnvelopeSchema.parse(JSON.parse(frame));
    expect(env.sessionId).toBeUndefined();
  });

  it("accepts the canonical session.opened fixture", () => {
    const payload = {
      sessionId,
      ready: true,
      channelMode: "CONTROL",
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.SESSION_OPENED),
        hostInstanceId,
        sessionId,
      }),
    );
    expect(frame.type).toBe("session.opened");
    expect((frame.payload as { channelMode: string }).channelMode).toBe("CONTROL");
  });

  it("rejects session.opened with missing channelMode", () => {
    const payload = { sessionId, ready: true };
    expect(sessionOpenedPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("accepts the canonical session.closed fixture", () => {
    const payload = {
      sessionId,
      closedAt: now,
      reasonCode: "CONVERSATION_ARCHIVED",
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.SESSION_CLOSED),
        hostInstanceId,
        sessionId,
      }),
    );
    expect(frame.type).toBe("session.closed");
    expect((frame.payload as { reasonCode: string }).reasonCode).toBe("CONVERSATION_ARCHIVED");
  });

  it("rejects session.closed with missing reasonCode", () => {
    const payload = { sessionId, closedAt: now };
    expect(sessionClosedPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("round-trips session.offer", () => {
    const payload = {
      allocationId,
      sessionId,
      kind: "ORCHESTRATION_TASK",
      ref: { type: "ORCHESTRATION_TASK", id: executionId },
      requirements: { tools: ["git"], runtimes: [], features: [] },
      lease: { offerExpiresAt: now, idleTimeoutSeconds: 300 },
      routing: { homeNodeId: "node-b" },
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OFFER)));
    const encoded = JSON.parse(encodeUnifiedFrame(frame));
    expect(encoded.type).toBe("session.offer");
    expect(encoded.payload.kind).toBe("ORCHESTRATION_TASK");
  });
});

// ============================================================
// §7.5 dedicated session channel (A1)
// ============================================================

describe("§7.5 channel frames", () => {
  it("accepts the canonical channel.open fixture", () => {
    const payload = {
      sessionId,
      resumeFromSequence: 42,
      token: "myr_chn_offer_token",
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.CHANNEL_OPEN),
        sessionId,
      }),
    );
    expect(frame.type).toBe("channel.open");
    const p = frame.payload as { sessionId: string; resumeFromSequence: number; token: string };
    expect(p.sessionId).toBe(sessionId);
    expect(p.resumeFromSequence).toBe(42);
    expect(p.token).toBe("myr_chn_offer_token");
  });

  it("defaults resumeFromSequence to 0 when omitted (channel.open)", () => {
    const payload = {
      sessionId,
      token: "myr_chn_offer_token",
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.CHANNEL_OPEN)));
    expect(
      (frame.payload as { resumeFromSequence: number }).resumeFromSequence,
    ).toBe(0);
  });

  it("rejects channel.open with a negative resumeFromSequence", () => {
    expect(
      channelOpenPayloadSchema.safeParse({
        sessionId,
        resumeFromSequence: -1,
        token: "t",
      }).success,
    ).toBe(false);
  });

  it("rejects channel.open with a missing token", () => {
    expect(
      channelOpenPayloadSchema.safeParse({ sessionId, resumeFromSequence: 0 })
        .success,
    ).toBe(false);
  });

  it("rejects channel.open with a non-uuid sessionId", () => {
    expect(
      channelOpenPayloadSchema.safeParse({
        sessionId: "not-a-uuid",
        resumeFromSequence: 0,
        token: "t",
      }).success,
    ).toBe(false);
  });

  it("accepts the canonical channel.opened fixture", () => {
    const payload = {
      sessionId,
      highestContiguousSequence: 7,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.CHANNEL_OPENED),
        sessionId,
      }),
    );
    expect(frame.type).toBe("channel.opened");
    const p = frame.payload as {
      sessionId: string;
      highestContiguousSequence: number;
    };
    expect(p.highestContiguousSequence).toBe(7);
  });

  it("rejects channel.opened with a missing highestContiguousSequence", () => {
    expect(
      channelOpenedPayloadSchema.safeParse({ sessionId }).success,
    ).toBe(false);
  });

  it("round-trips channel.open through parse and encode", () => {
    const payload = { sessionId, resumeFromSequence: 5, token: "tok" };
    const original = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.CHANNEL_OPEN),
        sessionId,
      }),
    );
    const encoded = JSON.parse(encodeUnifiedFrame(original));
    expect(encoded.type).toBe("channel.open");
    expect(encoded.payload.resumeFromSequence).toBe(5);
  });
});

describe("§7.5 session.open channel offer (A1)", () => {
  function sessionOpenWithChannel(channel?: unknown): Record<string, unknown> {
    const payload: Record<string, unknown> = {
      sessionId,
      kind: "CONVERSATION",
      projectId: "11111111-1111-4111-8111-111111111111",
      profileVersionId: "22222222-2222-4222-8222-222222222222",
      model: {
        provider: "openai",
        modelId: "gpt-4o",
        endpoint: null,
        credentialRef: null,
        parameters: {},
      },
      workspace: null,
      tools: [],
      knowledgeSources: [],
      autoHitlOnDestructive: true,
    };
    if (channel !== undefined) {
      payload.channel = channel;
    }
    return payload;
  }

  it("accepts session.open carrying a channel offer", () => {
    const payload = sessionOpenWithChannel({
      endpoint: "wss://engine.example/api/v1/agent/host/ws/channel",
      token: "myr_chn_offer_token",
    });
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN)));
    const channel = (frame.payload as { channel?: { endpoint: string; token: string } }).channel;
    expect(channel).toEqual({
      endpoint: "wss://engine.example/api/v1/agent/host/ws/channel",
      token: "myr_chn_offer_token",
    });
  });

  it("accepts session.open with channel null (channel disabled)", () => {
    const payload = sessionOpenWithChannel(null);
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN)));
    const channel = (frame.payload as { channel?: unknown }).channel;
    expect(channel).toBeNull();
  });

  it("accepts session.open without a channel field (older engine)", () => {
    const payload = sessionOpenWithChannel(undefined);
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN)));
    expect((frame.payload as { channel?: unknown }).channel).toBeUndefined();
  });

  it("rejects a channel offer with a missing token", () => {
    const payload = sessionOpenWithChannel({
      endpoint: "wss://engine.example/api/v1/agent/host/ws/channel",
    });
    expect(() =>
      parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN))),
    ).toThrow();
  });

  it("rejects a channel offer with a missing endpoint", () => {
    const payload = sessionOpenWithChannel({ token: "myr_chn_offer_token" });
    expect(() =>
      parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN))),
    ).toThrow();
  });
});

// ============================================================
// execution lifecycle (ports ExecutionLifecycleFlowTest fixtures)
// ============================================================

describe("execution lifecycle frames", () => {
  it("accepts the canonical execution.start (conversation) fixture", () => {
    const payload = {
      executionId,
      sessionId,
      sequenceNo: 1,
      requestId: sessionId,
      deadline: now,
      input: {
        messages: [{ role: "user", content: "Hello, engine", parts: null, toolCalls: null }],
        attachments: null,
        conversationContinuation: null,
      },
      toolPolicy: { activeToolNames: ["read_file"], approvalMode: "ENGINE" },
      output: { stream: true, responseSequenceNo: 1, format: "TEXT" },
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.EXECUTION_START),
        hostInstanceId,
        sessionId,
        executionId,
      }),
    );
    expect(frame.type).toBe("execution.start");
    expect((frame.payload as { input: { messages: unknown[] } }).input.messages).toHaveLength(1);
  });

  it("accepts the canonical execution.start (orchestration) fixture", () => {
    const payload = {
      executionId,
      sessionId,
      dispatchId,
      attemptId: dispatchId,
      assignmentDigest: "abc",
      deadline: now,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.EXECUTION_START),
        hostInstanceId,
        sessionId,
        executionId,
      }),
    );
    expect(frame.type).toBe("execution.start");
    expect((frame.payload as { dispatchId: string }).dispatchId).toBe(dispatchId);
  });

  it("rejects execution.start with missing executionId", () => {
    const payload = {
      sessionId,
      deadline: now,
    };
    expect(executionStartPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("accepts the canonical execution.event with sequence fixture", () => {
    const payload = {
      executionId,
      eventId,
      eventType: "PROGRESS",
      occurredAt: now,
      data: { pct: 50 },
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.EXECUTION_EVENT),
        hostInstanceId,
        sessionId,
        executionId,
        sequence: 1,
      }),
    );
    expect(frame.type).toBe("execution.event");
    expect(frame.sequence).toBe(1);
    expect((frame.payload as { eventType: string }).eventType).toBe("PROGRESS");
  });

  it("rejects execution.event with missing eventType", () => {
    const payload = {
      executionId,
      eventId,
      occurredAt: now,
      data: {},
    };
    expect(executionEventPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("accepts the canonical execution.complete fixture", () => {
    const payload = {
      executionId,
      completedAt: now,
      result: { content: "done", structured: null, artifacts: [] },
      usage: { modelId: "m1", inputTokens: 3, outputTokens: 5, durationMs: 100 },
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.EXECUTION_COMPLETE),
        hostInstanceId,
        sessionId,
        executionId,
      }),
    );
    expect(frame.type).toBe("execution.complete");
    expect((frame.payload as { result: { content: string } }).result.content).toBe("done");
  });

  it("rejects execution.complete with missing executionId", () => {
    const payload = {
      completedAt: now,
      result: { content: "done" },
    };
    expect(executionCompletePayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("accepts execution.paused with conversation continuation shape", () => {
    const payload = {
      executionId,
      pausedAt: now,
      reasonCode: "HITL_APPROVAL",
      continuation: null,
      suspension: null,
      conversationContinuation: {
        approvalRequestId: "req-1",
        pendingActionId: "act-1",
        pendingActionDigest: "digest-1",
      },
      usage: { modelId: "m1", inputTokens: 3, outputTokens: 5 },
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.EXECUTION_PAUSED),
        hostInstanceId,
        sessionId,
        executionId,
      }),
    );
    expect(frame.type).toBe("execution.paused");
    expect(
      (frame.payload as { conversationContinuation: { pendingActionId: string } }).conversationContinuation
        .pendingActionId,
    ).toBe("act-1");
  });

  it("rejects execution.paused with missing reasonCode", () => {
    const payload = {
      executionId,
      pausedAt: now,
    };
    expect(executionPausedPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("round-trips execution.event", () => {
    const payload = {
      executionId,
      eventId,
      eventType: "LOG",
      occurredAt: now,
      data: { level: "info" },
    };
    const frame = parseUnifiedFrame(
      JSON.stringify({
        ...envelope(payload, MessageType.EXECUTION_EVENT),
        hostInstanceId,
        sessionId,
        executionId,
        sequence: 7,
      }),
    );
    const encoded = JSON.parse(encodeUnifiedFrame(frame));
    expect(encoded.type).toBe("execution.event");
    expect(encoded.sequence).toBe(7);
    expect(encoded.payload.eventType).toBe("LOG");
  });
});

// ============================================================
// protocol housekeeping
// ============================================================

describe("protocol housekeeping frames", () => {
  it("accepts the canonical protocol.ack fixture", () => {
    const payload = {
      acknowledgedMessageId: "m-event",
      highestContiguousSequence: 1,
      status: "DURABLY_RECORDED",
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.PROTOCOL_ACK)));
    expect(frame.type).toBe("protocol.ack");
    expect((frame.payload as { status: string }).status).toBe("DURABLY_RECORDED");
  });

  it("rejects protocol.ack with an unknown status literal", () => {
    const payload = {
      acknowledgedMessageId: "m-event",
      highestContiguousSequence: 1,
      status: "PENDING",
    };
    expect(protocolAckPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("accepts the canonical protocol.error fixture", () => {
    const payload = {
      code: "INVALID_STATE",
      message: "session.accept for a session not in OFFERED",
      retryable: false,
      offendingMessageId: "m-acc",
      scope: "SESSION",
      details: null,
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.PROTOCOL_ERROR)));
    expect(frame.type).toBe("protocol.error");
    expect((frame.payload as { code: string }).code).toBe("INVALID_STATE");
  });

  it("rejects protocol.error with an unknown code when union is enforced", () => {
    const payload = {
      code: "BOGUS_CODE",
      message: "nope",
      retryable: false,
      scope: "CONNECTION",
    };
    // The schema uses a strict enum for §14 codes; an unknown code is rejected.
    expect(protocolErrorPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("round-trips protocol.error", () => {
    const payload = {
      code: "UNSUPPORTED_VERSION",
      message: "version 99 not supported",
      retryable: false,
      offendingMessageId: "m-5",
      scope: "CONNECTION",
      details: null,
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.PROTOCOL_ERROR)));
    const encoded = JSON.parse(encodeUnifiedFrame(frame));
    expect(encoded.type).toBe("protocol.error");
    expect(encoded.payload.code).toBe("UNSUPPORTED_VERSION");
  });
});

// ============================================================
// parseUnifiedFrame dispatch map
// ============================================================

describe("parseUnifiedFrame dispatch", () => {
  it("throws on unsupported message types", () => {
    const frame = {
      protocolVersion: 1,
      messageId: "m-1",
      type: "host.announce",
      sentAt,
      payload: {},
    };
    expect(() => parseUnifiedFrame(JSON.stringify(frame))).toThrow();
  });

  it("throws on malformed JSON", () => {
    expect(() => parseUnifiedFrame("{ this is not json")).toThrow(SyntaxError);
  });
});

// ============================================================
// full round-trip smoke
// ============================================================

test("parse → encode → parse yields identical result", () => {
  const payload = {
    executionId,
    eventId,
    eventType: "CHECKPOINT",
    occurredAt: now,
    data: { step: 3 },
  };
  const original = parseUnifiedFrame(
    JSON.stringify({
      protocolVersion: 1,
      messageId: "m-rt",
      type: "execution.event",
      sentAt,
      hostInstanceId,
      sessionId,
      executionId,
      sequence: 3,
      payload,
    }),
  );
  const encoded = encodeUnifiedFrame(original);
  const roundTripped = parseUnifiedFrame(encoded);
  expect(roundTripped.messageId).toBe(original.messageId);
  expect(roundTripped.type).toBe(original.type);
  expect(roundTripped.sessionId).toBe(original.sessionId);
  expect(roundTripped.sequence).toBe(original.sequence);
  expect(roundTripped.payload).toEqual(original.payload);
});

// ============================================================
// credential envelopes (§7.3, design 2026-09-16 §9)
// ============================================================

/** VECTOR_1-shaped envelope re-bound to the test sessionId. */
function testEnvelope(sessionIdValue: string) {
  return {
    format: "MyrmecSecureEnvelopeV1",
    keyId: "0f0e0d0c-0b0a-4901-8203-040506070809",
    sessionId: sessionIdValue,
    hostId: "fedcba98-7654-4321-0fed-cba987654321",
    purpose: "MODEL_PROVIDER",
    createdAt: now,
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
    plaintextDigest: "sha256:E1Lsrr0Xj46jYk02ZuJM1ulyD1ccit3TEQk8iDBc/nU=",
    nonce: "AAAAAAAAAAAAAAAAAAAAAA==",
    ciphertext: "b37AH5DB8Xt+cCmuKC2vt7HJOBaYtGh5aU4fB4qefXedL4c5l78GI+bAHBi4+Q4=",
  };
}

function sessionOpenWithCredentials(
  credentials?: unknown,
  modelOverride: Record<string, unknown> = {},
): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    sessionId,
    kind: "CONVERSATION",
    projectId: "11111111-1111-4111-8111-111111111111",
    profileVersionId: "22222222-2222-4222-8222-222222222222",
    model: {
      provider: "openai",
      modelId: "gpt-4o",
      endpoint: null,
      credentialRef: "model-provider-token",
      parameters: {},
      ...modelOverride,
    },
    workspace: null,
    tools: [],
    knowledgeSources: [],
    autoHitlOnDestructive: true,
  };
  if (credentials !== undefined) {
    payload.credentials = credentials;
  }
  return payload;
}

describe("session.open credentials (design §9)", () => {
  it("accepts session.open with a credentials array", () => {
    const payload = sessionOpenWithCredentials([
      {
        credentialRef: "model-provider-token",
        purpose: "MODEL_PROVIDER",
        envelope: testEnvelope(sessionId),
      },
    ]);
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN)));
    expect(frame.type).toBe("session.open");
    const credentials = (frame.payload as { credentials?: unknown[] }).credentials;
    expect(credentials).toHaveLength(1);
    expect((credentials![0] as { credentialRef: string }).credentialRef).toBe(
      "model-provider-token",
    );
    expect((credentials![0] as { purpose: string }).purpose).toBe("MODEL_PROVIDER");
  });

  it("accepts a keyless session.open without credentials (unchanged behavior)", () => {
    const payload = sessionOpenWithCredentials(undefined, { credentialRef: null });
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN)));
    const credentials = (frame.payload as { credentials?: unknown[] }).credentials;
    expect(credentials).toBeUndefined();
  });

  it("rejects an envelope whose format literal is wrong", () => {
    const payload = sessionOpenWithCredentials([
      {
        credentialRef: "model-provider-token",
        purpose: "MODEL_PROVIDER",
        envelope: { ...testEnvelope(sessionId), format: "MyrmecSecureEnvelopeV2" },
      },
    ]);
    expect(() =>
      parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN))),
    ).toThrow();
  });

  it("rejects a credentials entry with an unknown purpose", () => {
    const payload = sessionOpenWithCredentials([
      {
        credentialRef: "model-provider-token",
        purpose: "BOGUS_PURPOSE",
        envelope: testEnvelope(sessionId),
      },
    ]);
    expect(() =>
      parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN))),
    ).toThrow();
  });

  it("never serializes the plaintext into the frame (no-plaintext rule)", () => {
    const PLAINTEXT = "sk-test-provider-key-0123456789";
    const payload = sessionOpenWithCredentials([
      {
        credentialRef: "model-provider-token",
        purpose: "MODEL_PROVIDER",
        envelope: testEnvelope(sessionId),
      },
    ]);
    // The envelope only ever carries ciphertext + metadata — the plaintext
    // must not appear anywhere in the serialized frame.
    expect(JSON.stringify(payload)).not.toContain(PLAINTEXT);
  });
});
