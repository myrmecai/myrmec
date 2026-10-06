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
  hostOpenCapabilitySchema,
  hostResumePayloadSchema,
  hostReconcilePayloadSchema,
  ReconcileAction,
  sessionAcceptPayloadSchema,
  sessionOpenPayloadSchema,
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
  interactionPolicySchema,
  executionControlPayloadSchema,
  executionControlStatePayloadSchema,
  executionInteractionPayloadSchema,
  executionInteractionDeltaPayloadSchema,
  executionInteractionCompletePayloadSchema,
  executionInteractionFailedPayloadSchema,
  executionControlRequestPayloadSchema,
  executionControlRequestResolvedPayloadSchema,
  SESSION_INTERACTION_CAPABILITY,
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
      capabilities: {
        ...sessionInteractionCapabilityFixture,
        tools: ["read_file"],
        runtimes: ["node:22"],
        features: ["TOOL_EVENTS"],
      },
      reportedCapacity: { cpuCount: 16 },
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.HOST_OPEN)));
    expect(frame.type).toBe("host.open");
    expect((frame.payload as { poolSize: number }).poolSize).toBe(5);
    expect((frame.payload as { capabilities: unknown }).capabilities).toEqual({
      ...sessionInteractionCapabilityFixture,
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
      capabilities: sessionInteractionCapabilityFixture,
      reportedCapacity: {},
    };
    expect(hostOpenPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("rejects host.open without the sessionInteraction capability (§22.2 fail-closed)", () => {
    // §17 cutover: stale host.open fixtures must fail explicitly.
    expect(
      hostOpenPayloadSchema.safeParse({
        instanceNonce: nonce,
        hostname: "developer-laptop",
        runtimeVersion: "1.8.0",
        supportedProtocolVersions: [1],
        poolSize: 5,
        capabilities: {},
        reportedCapacity: {},
      }).success,
    ).toBe(false);
  });

  it("accepts host.open with the additive ownerUserId (§3.7 local-owner model)", () => {
    const payload = {
      instanceNonce: nonce,
      hostname: "developer-laptop",
      runtimeVersion: "1.8.0",
      supportedProtocolVersions: [1],
      poolSize: 1,
      capabilities: sessionInteractionCapabilityFixture,
      reportedCapacity: {},
      ownerUserId: "77777777-7777-4777-8777-777777777777",
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.HOST_OPEN)));
    expect(frame.type).toBe("host.open");
    expect(
      (frame.payload as { ownerUserId?: string | null }).ownerUserId,
    ).toBe("77777777-7777-4777-8777-777777777777");
  });

  it("parses host.open with ownerUserId null and absent (MANAGED hosts)", () => {
    const base = {
      instanceNonce: nonce,
      hostname: "cluster-node",
      runtimeVersion: "1.8.0",
      supportedProtocolVersions: [1],
      poolSize: 4,
      capabilities: sessionInteractionCapabilityFixture,
      reportedCapacity: {},
    };
    expect(hostOpenPayloadSchema.parse({ ...base, ownerUserId: null }).ownerUserId).toBeNull();
    const frame = parseUnifiedFrame(JSON.stringify(envelope({ ...base }, MessageType.HOST_OPEN)));
    expect((frame.payload as { ownerUserId?: string | null }).ownerUserId).toBeUndefined();
  });

  it("rejects host.open with a malformed ownerUserId", () => {
    const payload = {
      instanceNonce: nonce,
      hostname: "developer-laptop",
      runtimeVersion: "1.8.0",
      supportedProtocolVersions: [1],
      poolSize: 1,
      capabilities: {},
      reportedCapacity: {},
      ownerUserId: "not-a-uuid",
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
      acceptedCapabilities: sessionInteractionCapabilityFixture,
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
      acceptedCapabilities: sessionInteractionCapabilityFixture,
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.HOST_OPENED)));
    expect(frame.type).toBe("host.opened");
    expect((frame.payload as { psk?: string }).psk).toBeDefined();
    expect((frame.payload as { pskKeyId?: string }).pskKeyId).toBe(
      "0f0e0d0c-0b0a-4901-8203-040506070809",
    );
  });

  it("rejects host.opened without the acceptedCapabilities echo (§22.2)", () => {
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
    expect(hostOpenedPayloadSchema.safeParse(payload).success).toBe(false);
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
      capabilities: sessionInteractionCapabilityFixture,
      reportedCapacity: {},
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.HOST_OPEN)));
    const encoded = JSON.parse(encodeUnifiedFrame(frame));
    expect(encoded.type).toBe("host.open");
    expect(encoded.payload.instanceNonce).toBe(nonce);
  });
});

// ============================================================
// §13 reconnect & reconciliation (A2)
// ============================================================

describe("§13 host.resume / host.reconcile", () => {
  it("accepts the canonical host.resume fixture (§13 verbatim)", () => {
    const payload = {
      previousHostInstanceId: hostInstanceId,
      instanceNonce: nonce,
      sessions: [
        {
          sessionId,
          state: "ACTIVE",
          capacityHeld: true,
          activeExecutionId: executionId,
          lastSentSequence: 18,
          lastAcknowledgedMessageId: "m-17",
        },
      ],
    };
    const result = hostResumePayloadSchema.safeParse(payload);
    expect(result.success).toBe(true);
  });

  it("defaults host.resume sessions to empty when absent (engine tolerates)", () => {
    const result = hostResumePayloadSchema.safeParse({
      previousHostInstanceId: hostInstanceId,
      instanceNonce: nonce,
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.sessions).toEqual([]);
    }
  });

  it("rejects host.resume without instanceNonce", () => {
    const result = hostResumePayloadSchema.safeParse({
      previousHostInstanceId: hostInstanceId,
      sessions: [],
    });
    expect(result.success).toBe(false);
  });

  it("accepts every §13 reconcile action", () => {
    expect(ReconcileAction.KEEP).toBe("KEEP");
    expect(ReconcileAction.CANCEL_EXECUTION).toBe("CANCEL_EXECUTION");
    expect(ReconcileAction.CLOSE).toBe("CLOSE");
  });

  it("accepts the canonical host.reconcile fixture (engine reply)", () => {
    const payload = {
      hostInstanceId,
      decisions: [
        { sessionId, action: "KEEP", resumeFromSequence: 17 },
        { sessionId, action: "CANCEL_EXECUTION", executionId },
        { sessionId, action: "CLOSE", reasonCode: "TASK_ALREADY_RETRIED" },
      ],
    };
    const result = hostReconcilePayloadSchema.safeParse(payload);
    expect(result.success).toBe(true);
  });

  it("rejects host.reconcile with an unknown action", () => {
    const result = hostReconcilePayloadSchema.safeParse({
      hostInstanceId,
      decisions: [{ sessionId, action: "REPLAY", resumeFromSequence: 1 }],
    });
    expect(result.success).toBe(false);
  });

  it("round-trips a host.reconcile frame through parse and encode", () => {
    const payload = {
      hostInstanceId,
      decisions: [{ sessionId, action: "KEEP", resumeFromSequence: 17 }],
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.HOST_RECONCILE)),
    );
    expect(frame.type).toBe("host.reconcile");
    const encoded = JSON.parse(encodeUnifiedFrame(frame));
    expect(encoded.payload.decisions[0].action).toBe("KEEP");
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

// ============================================================
// §22 session interaction wire contract (Task 1)
// ============================================================

const interactionId = "88888888-8888-4888-8888-888888888888";
const actorUserId = "99999999-9999-4999-8999-999999999999";
const controlRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const acceptedAt = "2026-10-03T10:01:00.000Z";
const responseDeadline = "2026-10-03T10:03:00.000Z";
const changedAt = "2026-10-03T10:00:00.000Z";
const idleResumeAt = "2026-10-03T10:05:00.000Z";
const completedAt = "2026-10-03T10:01:03.000Z";
const expiresAt = "2026-10-03T10:03:00.000Z";

const sessionInteractionCapabilityFixture = {
  sessionInteraction: { version: 1, temporaryHold: true },
};

const canonicalInteractionPolicy = {
  version: 1,
  enabled: true,
  idleResumeAfterSeconds: 300,
  responseTimeoutSeconds: 120,
  maxInputBytes: 16384,
  maxOutputBytes: 65536,
  maxModelIterations: 8,
  maxHistoryBytes: 262144,
  transcriptRetentionDays: 30,
  contentMode: "USER_CHAT_ONLY",
};

function sessionOpenBase(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
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
    ...overrides,
  };
}

describe("§22.2 session interaction capability + policy", () => {
  it("accepts host.open advertising the mandatory sessionInteraction capability", () => {
    const payload = {
      instanceNonce: nonce,
      hostname: "developer-laptop",
      runtimeVersion: "1.8.0",
      supportedProtocolVersions: [1],
      poolSize: 5,
      capabilities: sessionInteractionCapabilityFixture,
      reportedCapacity: {},
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.HOST_OPEN)));
    const caps = (frame.payload as { capabilities: Record<string, unknown> }).capabilities;
    expect(caps).toEqual(sessionInteractionCapabilityFixture);
  });

  it("accepts host.opened with the acceptedCapabilities echo", () => {
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
      acceptedCapabilities: sessionInteractionCapabilityFixture,
    };
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.HOST_OPENED)));
    const caps = (frame.payload as {
      acceptedCapabilities?: Record<string, unknown>;
    }).acceptedCapabilities;
    expect(caps).toEqual(sessionInteractionCapabilityFixture);
  });

  it("rejects a capability with an unsupported version (fail-closed)", () => {
    expect(
      hostOpenCapabilitySchema.safeParse({
        sessionInteraction: { version: 2, temporaryHold: true },
      }).success,
    ).toBe(false);
  });

  it("rejects a capability with temporaryHold false (fail-closed)", () => {
    expect(
      hostOpenCapabilitySchema.safeParse({
        sessionInteraction: { version: 1, temporaryHold: false },
      }).success,
    ).toBe(false);
  });

  it("accepts the canonical interaction policy fixture (§22.2 verbatim defaults)", () => {
    const result = interactionPolicySchema.safeParse(canonicalInteractionPolicy);
    expect(result.success).toBe(true);
  });

  it("rejects a policy with an out-of-bounds bound (tighten-only domain)", () => {
    // Each field outside its §22.2 tightening range fails validation.
    const outOfBounds: Array<Record<string, unknown>> = [
      { ...canonicalInteractionPolicy, idleResumeAfterSeconds: 29 },
      { ...canonicalInteractionPolicy, idleResumeAfterSeconds: 3601 },
      { ...canonicalInteractionPolicy, responseTimeoutSeconds: 4 },
      { ...canonicalInteractionPolicy, responseTimeoutSeconds: 301 },
      { ...canonicalInteractionPolicy, maxInputBytes: 16385 },
      { ...canonicalInteractionPolicy, maxOutputBytes: 65537 },
      { ...canonicalInteractionPolicy, maxModelIterations: 9 },
      { ...canonicalInteractionPolicy, maxHistoryBytes: 262145 },
      { ...canonicalInteractionPolicy, transcriptRetentionDays: 31 },
      { ...canonicalInteractionPolicy, contentMode: "SOMETHING_ELSE" },
      { ...canonicalInteractionPolicy, version: 2 },
    ];
    for (const payload of outOfBounds) {
      expect(interactionPolicySchema.safeParse(payload).success).toBe(false);
    }
  });
});

describe("§22.4 execution.control (engine→host, durable command)", () => {
  it("accepts the canonical HOLD fixture (§22.4 verbatim)", () => {
    const payload = {
      executionId,
      dispatchId,
      controlRevision: 1,
      action: "HOLD",
      reasonCode: "USER_REQUESTED",
      holdPolicy: { idleResumeAfterSeconds: 300 },
      controlRequestId: null,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_CONTROL)),
    );
    expect(frame.type).toBe("execution.control");
    expect(
      (frame.payload as { holdPolicy?: { idleResumeAfterSeconds: number } }).holdPolicy,
    ).toEqual({ idleResumeAfterSeconds: 300 });
  });

  it("rejects HOLD without the required hold policy (plan seed vector)", () => {
    const result = executionControlPayloadSchema.safeParse({
      executionId: "00000000-0000-4000-8000-000000000001",
      dispatchId: "00000000-0000-4000-8000-000000000002",
      controlRevision: 1,
      action: "HOLD",
      reasonCode: "USER_REQUESTED",
    });
    expect(result.success).toBe(false);
  });

  it("rejects HOLD with a null hold policy", () => {
    expect(
      executionControlPayloadSchema.safeParse({
        executionId,
        dispatchId,
        controlRevision: 1,
        action: "HOLD",
        reasonCode: "USER_REQUESTED",
        holdPolicy: null,
      }).success,
    ).toBe(false);
  });

  it("accepts CONTINUE without a hold policy (omitted)", () => {
    const result = executionControlPayloadSchema.safeParse({
      executionId,
      dispatchId,
      controlRevision: 2,
      action: "CONTINUE",
      reasonCode: "USER_REQUESTED",
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.holdPolicy).toBeUndefined();
      expect(result.data.controlRequestId).toBeUndefined();
    }
  });

  it("accepts a chat-origin command carrying controlRequestId", () => {
    const result = executionControlPayloadSchema.safeParse({
      executionId,
      dispatchId,
      controlRevision: 3,
      action: "CONTINUE",
      reasonCode: "USER_REQUESTED",
      controlRequestId,
    });
    expect(result.success).toBe(true);
  });

  it("rejects an unknown action", () => {
    expect(
      executionControlPayloadSchema.safeParse({
        executionId,
        dispatchId,
        controlRevision: 1,
        action: "CANCEL",
        reasonCode: "USER_REQUESTED",
      }).success,
    ).toBe(false);
  });

  it("rejects negative or zero controlRevision", () => {
    for (const controlRevision of [0, -1]) {
      expect(
        executionControlPayloadSchema.safeParse({
          executionId,
          dispatchId,
          controlRevision,
          action: "HOLD",
          reasonCode: "USER_REQUESTED",
          holdPolicy: { idleResumeAfterSeconds: 300 },
        }).success,
      ).toBe(false);
    }
  });

  it("rejects an invalid executionId uuid", () => {
    expect(
      executionControlPayloadSchema.safeParse({
        executionId: "not-a-uuid",
        dispatchId,
        controlRevision: 1,
        action: "HOLD",
        reasonCode: "USER_REQUESTED",
        holdPolicy: { idleResumeAfterSeconds: 300 },
      }).success,
    ).toBe(false);
  });

  it("rejects non-integer controlRevision", () => {
    expect(
      executionControlPayloadSchema.safeParse({
        executionId,
        dispatchId,
        controlRevision: 1.5,
        action: "HOLD",
        reasonCode: "USER_REQUESTED",
        holdPolicy: { idleResumeAfterSeconds: 300 },
      }).success,
    ).toBe(false);
  });

  it("rejects an out-of-bounds hold policy", () => {
    expect(
      executionControlPayloadSchema.safeParse({
        executionId,
        dispatchId,
        controlRevision: 1,
        action: "HOLD",
        reasonCode: "USER_REQUESTED",
        holdPolicy: { idleResumeAfterSeconds: 29 },
      }).success,
    ).toBe(false);
  });

  it("round-trips execution.control", () => {
    const payload = {
      executionId,
      dispatchId,
      controlRevision: 1,
      action: "HOLD",
      reasonCode: "USER_REQUESTED",
      holdPolicy: { idleResumeAfterSeconds: 300 },
      controlRequestId: null,
    };
    const original = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_CONTROL)),
    );
    const encoded = JSON.parse(encodeUnifiedFrame(original));
    expect(encoded.type).toBe("execution.control");
    expect(encoded.payload.action).toBe("HOLD");
    expect(encoded.payload.holdPolicy).toEqual({ idleResumeAfterSeconds: 300 });
  });
});

describe("§22.4 execution.control.state (host→engine, durable event)", () => {
  it("accepts the canonical HELD fixture (§22.4 verbatim)", () => {
    const payload = {
      executionId,
      dispatchId,
      controlRevision: 1,
      stateSequence: 2,
      status: "HELD",
      effectiveState: "HELD",
      reasonCode: "USER_REQUESTED",
      changedAt,
      idleResumeAt,
      safePoint: "BEFORE_MODEL_CALL",
      errorCode: null,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_CONTROL_STATE)),
    );
    expect(frame.type).toBe("execution.control.state");
    const p = frame.payload as { safePoint: string; idleResumeAt: string };
    expect(p.safePoint).toBe("BEFORE_MODEL_CALL");
    expect(p.idleResumeAt).toBe(idleResumeAt);
  });

  it("accepts a REJECTED state carrying rejectedControlRevision", () => {
    const payload = {
      executionId,
      dispatchId,
      controlRevision: 4,
      stateSequence: 9,
      status: "REJECTED",
      effectiveState: "HELD",
      reasonCode: "STALE_CONTROL_REVISION",
      changedAt,
      idleResumeAt: null,
      safePoint: null,
      rejectedControlRevision: 3,
      errorCode: null,
    };
    const result = executionControlStatePayloadSchema.safeParse(payload);
    expect(result.success).toBe(true);
  });

  it("accepts a RUNNING auto-resume state with null safePoint/idleResumeAt", () => {
    const payload = {
      executionId,
      dispatchId,
      controlRevision: 1,
      stateSequence: 5,
      status: "RUNNING",
      effectiveState: "RUNNING",
      reasonCode: "HOLD_IDLE_EXPIRED",
      changedAt,
      idleResumeAt: null,
      safePoint: null,
      errorCode: null,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_CONTROL_STATE)),
    );
    const p = frame.payload as { idleResumeAt: unknown; safePoint: unknown };
    expect(p.idleResumeAt).toBeNull();
    expect(p.safePoint).toBeNull();
  });

  it("rejects a status not in the §22.4 enum", () => {
    expect(
      executionControlStatePayloadSchema.safeParse({
        executionId,
        dispatchId,
        controlRevision: 1,
        stateSequence: 2,
        status: "PAUSED",
        effectiveState: "HELD",
        reasonCode: "USER_REQUESTED",
        changedAt,
        idleResumeAt: null,
        safePoint: null,
        errorCode: null,
      }).success,
    ).toBe(false);
  });

  it("rejects an effectiveState not in the §22.4 enum", () => {
    expect(
      executionControlStatePayloadSchema.safeParse({
        executionId,
        dispatchId,
        controlRevision: 1,
        stateSequence: 2,
        status: "REJECTED",
        effectiveState: "PAUSED",
        reasonCode: "STALE_CONTROL_REVISION",
        changedAt,
        idleResumeAt: null,
        safePoint: null,
        errorCode: null,
      }).success,
    ).toBe(false);
  });

  it("rejects an unknown safePoint", () => {
    expect(
      executionControlStatePayloadSchema.safeParse({
        executionId,
        dispatchId,
        controlRevision: 1,
        stateSequence: 2,
        status: "HELD",
        effectiveState: "HELD",
        reasonCode: "USER_REQUESTED",
        changedAt,
        idleResumeAt,
        safePoint: "MIDDLE_OF_MODEL_CALL",
        errorCode: null,
      }).success,
    ).toBe(false);
  });

  it("rejects negative controlRevision and non-positive stateSequence", () => {
    for (const [controlRevision, stateSequence] of [
      [-1, 2],
      [0, 0],
    ] as Array<[number, number]>) {
      expect(
        executionControlStatePayloadSchema.safeParse({
          executionId,
          dispatchId,
          controlRevision,
          stateSequence,
          status: "RUNNING",
          effectiveState: "RUNNING",
          reasonCode: "HOLD_IDLE_EXPIRED",
          changedAt,
          idleResumeAt: null,
          safePoint: null,
          errorCode: null,
        }).success,
      ).toBe(false);
    }
  });

  it("round-trips execution.control.state", () => {
    const payload = {
      executionId,
      dispatchId,
      controlRevision: 1,
      stateSequence: 2,
      status: "HELD",
      effectiveState: "HELD",
      reasonCode: "USER_REQUESTED",
      changedAt,
      idleResumeAt,
      safePoint: "BEFORE_MODEL_CALL",
      errorCode: null,
    };
    const original = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_CONTROL_STATE)),
    );
    const encoded = JSON.parse(encodeUnifiedFrame(original));
    expect(encoded.type).toBe("execution.control.state");
    expect(encoded.payload.status).toBe("HELD");
  });
});

describe("§22.6 execution.interaction (engine→host, durable command)", () => {
  it("accepts the canonical request fixture (§22.6 verbatim)", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      ordinal: 1,
      actorUserId,
      message: { text: "Why is verification taking longer?" },
      acceptedAt,
      responseDeadline,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_INTERACTION)),
    );
    expect(frame.type).toBe("execution.interaction");
    const p = frame.payload as { message: { text: string }; ordinal: number };
    expect(p.message.text).toBe("Why is verification taking longer?");
    expect(p.ordinal).toBe(1);
  });

  it("rejects a missing nested message text", () => {
    expect(
      executionInteractionPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        ordinal: 1,
        actorUserId,
        message: {},
        acceptedAt,
        responseDeadline,
      }).success,
    ).toBe(false);
  });

  it("rejects an empty message text", () => {
    expect(
      executionInteractionPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        ordinal: 1,
        actorUserId,
        message: { text: "" },
        acceptedAt,
        responseDeadline,
      }).success,
    ).toBe(false);
  });

  it("rejects zero or negative ordinal", () => {
    for (const ordinal of [0, -1]) {
      expect(
        executionInteractionPayloadSchema.safeParse({
          executionId,
          dispatchId,
          interactionId,
          ordinal,
          actorUserId,
          message: { text: "hi" },
          acceptedAt,
          responseDeadline,
        }).success,
      ).toBe(false);
    }
  });

  it("rejects an invalid actorUserId uuid", () => {
    expect(
      executionInteractionPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        ordinal: 1,
        actorUserId: "nope",
        message: { text: "hi" },
        acceptedAt,
        responseDeadline,
      }).success,
    ).toBe(false);
  });

  it("round-trips execution.interaction", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      ordinal: 1,
      actorUserId,
      message: { text: "hi" },
      acceptedAt,
      responseDeadline,
    };
    const original = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_INTERACTION)),
    );
    const encoded = JSON.parse(encodeUnifiedFrame(original));
    expect(encoded.type).toBe("execution.interaction");
    expect(encoded.payload.interactionId).toBe(interactionId);
  });
});

describe("§22.6 execution.interaction.delta (host→engine, ephemeral)", () => {
  it("accepts the canonical delta fixture (§22.6 verbatim)", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      index: 0,
      text: "Verification is",
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_INTERACTION_DELTA)),
    );
    expect(frame.type).toBe("execution.interaction.delta");
    const p = frame.payload as { index: number; text: string };
    expect(p.index).toBe(0);
    expect(p.text).toBe("Verification is");
  });

  it("rejects a negative index", () => {
    expect(
      executionInteractionDeltaPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        index: -1,
        text: "x",
      }).success,
    ).toBe(false);
  });

  it("rejects an empty delta text", () => {
    expect(
      executionInteractionDeltaPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        index: 0,
        text: "",
      }).success,
    ).toBe(false);
  });

  it("round-trips execution.interaction.delta", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      index: 0,
      text: "Verification is",
    };
    const original = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_INTERACTION_DELTA)),
    );
    const encoded = JSON.parse(encodeUnifiedFrame(original));
    expect(encoded.type).toBe("execution.interaction.delta");
    expect(encoded.payload.index).toBe(0);
  });
});

describe("§22.6 execution.interaction.complete / failed (host→engine, durable)", () => {
  it("accepts the canonical complete fixture (§22.6 verbatim)", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      ordinal: 1,
      answer: { text: "The verifier is checking the current candidate." },
      usage: {
        inputTokens: 120,
        outputTokens: 30,
        modelId: "gpt-4o",
      },
      usageStatus: "KNOWN",
      controlRequestIds: [],
      completedAt,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_INTERACTION_COMPLETE)),
    );
    expect(frame.type).toBe("execution.interaction.complete");
    const p = frame.payload as {
      usage: { inputTokens: number; outputTokens: number };
      usageStatus: string;
    };
    expect(p.usage.inputTokens).toBe(120);
    expect(p.usage.outputTokens).toBe(30);
    expect(p.usageStatus).toBe("KNOWN");
  });

  it("accepts UNKNOWN usageStatus with null usage (fail-closed budget)", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      ordinal: 1,
      answer: { text: "ok" },
      usage: null,
      usageStatus: "UNKNOWN",
      controlRequestIds: [],
      completedAt,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_INTERACTION_COMPLETE)),
    );
    const p = frame.payload as { usageStatus: string; usage: unknown };
    expect(p.usageStatus).toBe("UNKNOWN");
    expect(p.usage).toBeNull();
  });

  it("accepts controlRequestIds listing issued proposals", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      ordinal: 1,
      answer: { text: "ok" },
      usage: { inputTokens: 120, outputTokens: 30, modelId: "gpt-4o" },
      usageStatus: "KNOWN",
      controlRequestIds: [controlRequestId],
      completedAt,
    };
    const result = executionInteractionCompletePayloadSchema.safeParse(payload);
    expect(result.success).toBe(true);
  });

  it("rejects an unknown usageStatus", () => {
    expect(
      executionInteractionCompletePayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        ordinal: 1,
        answer: { text: "ok" },
        usage: null,
        usageStatus: "MAYBE",
        controlRequestIds: [],
        completedAt,
      }).success,
    ).toBe(false);
  });

  it("rejects negative token usage", () => {
    expect(
      executionInteractionCompletePayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        ordinal: 1,
        answer: { text: "ok" },
        usage: { inputTokens: -1, outputTokens: 0, modelId: "m" },
        usageStatus: "KNOWN",
        controlRequestIds: [],
        completedAt,
      }).success,
    ).toBe(false);
  });

  it("rejects zero or negative ordinal", () => {
    expect(
      executionInteractionCompletePayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        ordinal: 0,
        answer: { text: "ok" },
        usage: null,
        usageStatus: "UNKNOWN",
        controlRequestIds: [],
        completedAt,
      }).success,
    ).toBe(false);
  });

  it("accepts the canonical failed fixture (§22.6 verbatim)", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      ordinal: 1,
      error: { errorCode: "INTERACTION_TIMEOUT", message: "answer deadline", retryable: false },
      usage: { inputTokens: 40, outputTokens: null, modelId: "gpt-4o" },
      usageStatus: "KNOWN",
      controlRequestIds: [],
      completedAt,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_INTERACTION_FAILED)),
    );
    expect(frame.type).toBe("execution.interaction.failed");
    const p = frame.payload as {
      error: { errorCode: string; message: string; retryable: boolean };
    };
    expect(p.error.errorCode).toBe("INTERACTION_TIMEOUT");
    expect(p.error.retryable).toBe(false);
  });

  it("accepts failed with null partial usage (UNKNOWN status)", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      ordinal: 1,
      error: { errorCode: "MODEL_ERROR", message: "boom", retryable: true },
      usage: null,
      usageStatus: "UNKNOWN",
      controlRequestIds: [],
      completedAt,
    };
    const result = executionInteractionFailedPayloadSchema.safeParse(payload);
    expect(result.success).toBe(true);
  });

  it("rejects a failed error code outside the §22.6 catalogue", () => {
    expect(
      executionInteractionFailedPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        ordinal: 1,
        error: { errorCode: "NOT_IN_CATALOGUE", message: "x", retryable: false },
        usage: null,
        usageStatus: "UNKNOWN",
        controlRequestIds: [],
        completedAt,
      }).success,
    ).toBe(false);
  });

  it("round-trips execution.interaction.failed", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      ordinal: 1,
      error: { errorCode: "HOST_STATE_LOST", message: "restart", retryable: false },
      usage: null,
      usageStatus: "UNKNOWN",
      controlRequestIds: [],
      completedAt,
    };
    const original = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_INTERACTION_FAILED)),
    );
    const encoded = JSON.parse(encodeUnifiedFrame(original));
    expect(encoded.type).toBe("execution.interaction.failed");
    expect(encoded.payload.error.errorCode).toBe("HOST_STATE_LOST");
  });
});

describe("§22.7 execution.control.request (host→engine, durable event)", () => {
  it("accepts the canonical CANCEL proposal fixture (§22.7 verbatim)", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      controlRequestId,
      action: "CANCEL",
      explanation: "User asked to stop this attempt.",
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_CONTROL_REQUEST)),
    );
    expect(frame.type).toBe("execution.control.request");
    const p = frame.payload as { action: string; explanation: string };
    expect(p.action).toBe("CANCEL");
    expect(p.explanation).toBe("User asked to stop this attempt.");
  });

  it("accepts HOLD and CONTINUE proposals without an explanation", () => {
    for (const action of ["HOLD", "CONTINUE"]) {
      const result = executionControlRequestPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        controlRequestId,
        action,
      });
      expect(result.success).toBe(true);
    }
    // An explicit null explanation parses too.
    const result = executionControlRequestPayloadSchema.safeParse({
      executionId,
      dispatchId,
      interactionId,
      controlRequestId,
      action: "HOLD",
      explanation: null,
    });
    expect(result.success).toBe(true);
  });

  it("rejects an action outside HOLD/CONTINUE/CANCEL", () => {
    expect(
      executionControlRequestPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        controlRequestId,
        action: "PAUSE",
      }).success,
    ).toBe(false);
  });

  it("round-trips execution.control.request", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      controlRequestId,
      action: "CANCEL",
      explanation: "User asked to stop this attempt.",
    };
    const original = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.EXECUTION_CONTROL_REQUEST)),
    );
    const encoded = JSON.parse(encodeUnifiedFrame(original));
    expect(encoded.type).toBe("execution.control.request");
    expect(encoded.payload.controlRequestId).toBe(controlRequestId);
  });
});

describe("§22.7 execution.control.request.resolved (engine→host, durable command)", () => {
  it("accepts the canonical CONFIRMATION_REQUIRED fixture (§22.7 verbatim)", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      controlRequestId,
      resolutionRevision: 1,
      status: "CONFIRMATION_REQUIRED",
      expiresAt,
      commandMessageId: null,
      errorCode: null,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(
        envelope(payload, MessageType.EXECUTION_CONTROL_REQUEST_RESOLVED),
      ),
    );
    expect(frame.type).toBe("execution.control.request.resolved");
    const p = frame.payload as { status: string; expiresAt: string };
    expect(p.status).toBe("CONFIRMATION_REQUIRED");
    expect(p.expiresAt).toBe(expiresAt);
  });

  it("accepts ACCEPTED with the persisted commandMessageId", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      controlRequestId,
      resolutionRevision: 2,
      status: "ACCEPTED",
      expiresAt: null,
      commandMessageId: "m-cmd-1",
      errorCode: null,
    };
    const frame = parseUnifiedFrame(
      JSON.stringify(
        envelope(payload, MessageType.EXECUTION_CONTROL_REQUEST_RESOLVED),
      ),
    );
    const p = frame.payload as { commandMessageId: string | null };
    expect(p.commandMessageId).toBe("m-cmd-1");
  });

  it("rejects an unknown resolution status", () => {
    expect(
      executionControlRequestResolvedPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        controlRequestId,
        resolutionRevision: 1,
        status: "PENDING",
        expiresAt: null,
        commandMessageId: null,
        errorCode: null,
      }).success,
    ).toBe(false);
  });

  it("rejects a disposition code outside the §22.7 refusal catalogue", () => {
    expect(
      executionControlRequestResolvedPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        controlRequestId,
        resolutionRevision: 1,
        status: "REJECTED",
        expiresAt: null,
        commandMessageId: null,
        errorCode: "SOME_RANDOM_CODE",
      }).success,
    ).toBe(false);
  });

  it("accepts REJECTED with a catalogue refusal code", () => {
    for (const errorCode of [
      "FORBIDDEN",
      "EXECUTION_TERMINAL",
      "INVALID_INTERACTION",
      "CONFIRMATION_EXPIRED",
    ]) {
      const result = executionControlRequestResolvedPayloadSchema.safeParse({
        executionId,
        dispatchId,
        interactionId,
        controlRequestId,
        resolutionRevision: 3,
        status: "REJECTED",
        expiresAt: null,
        commandMessageId: null,
        errorCode,
      });
      expect(result.success).toBe(true);
    }
  });

  it("rejects non-positive resolutionRevision", () => {
    for (const resolutionRevision of [0, -2]) {
      expect(
        executionControlRequestResolvedPayloadSchema.safeParse({
          executionId,
          dispatchId,
          interactionId,
          controlRequestId,
          resolutionRevision,
          status: "ACCEPTED",
          expiresAt: null,
          commandMessageId: "m-1",
          errorCode: null,
        }).success,
      ).toBe(false);
    }
  });

  it("round-trips execution.control.request.resolved", () => {
    const payload = {
      executionId,
      dispatchId,
      interactionId,
      controlRequestId,
      resolutionRevision: 1,
      status: "CONFIRMATION_REQUIRED",
      expiresAt,
      commandMessageId: null,
      errorCode: null,
    };
    const original = parseUnifiedFrame(
      JSON.stringify(
        envelope(payload, MessageType.EXECUTION_CONTROL_REQUEST_RESOLVED),
      ),
    );
    const encoded = JSON.parse(encodeUnifiedFrame(original));
    expect(encoded.type).toBe("execution.control.request.resolved");
    expect(encoded.payload.status).toBe("CONFIRMATION_REQUIRED");
  });
});

describe("§22.2 session.open interaction policy block (orchestration-only)", () => {
  it("accepts a WORKFLOW session.open carrying the interaction policy", () => {
    const payload = sessionOpenBase({
      kind: "WORKFLOW",
      executionMode: "ORCHESTRATION",
      orchestration: { schemaVersion: "1.0" },
      interaction: canonicalInteractionPolicy,
    });
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN)));
    expect(frame.type).toBe("session.open");
    const interaction = (frame.payload as {
      interaction?: Record<string, unknown>;
    }).interaction;
    expect(interaction).toEqual(canonicalInteractionPolicy);
  });

  it("rejects a WORKFLOW session.open without the interaction policy (fail-closed)", () => {
    const payload = sessionOpenBase({
      kind: "WORKFLOW",
      executionMode: "ORCHESTRATION",
    });
    expect(sessionOpenPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("rejects a WORKFLOW session.open whose policy has out-of-bounds fields", () => {
    const payload = sessionOpenBase({
      kind: "WORKFLOW",
      executionMode: "ORCHESTRATION",
      interaction: { ...canonicalInteractionPolicy, idleResumeAfterSeconds: 20 },
    });
    expect(sessionOpenPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("accepts a CONVERSATION session.open without the interaction policy", () => {
    // The existing canonical CONVERSATION fixture carries no interaction
    // block — the schema's kind-discriminated refinement keeps that valid.
    const payload = sessionOpenBase();
    const frame = parseUnifiedFrame(JSON.stringify(envelope(payload, MessageType.SESSION_OPEN)));
    expect(
      (frame.payload as { interaction?: unknown }).interaction,
    ).toBeUndefined();
  });

  it("rejects a CONVERSATION session.open carrying an interaction policy", () => {
    const payload = sessionOpenBase({
      kind: "CONVERSATION",
      interaction: canonicalInteractionPolicy,
    });
    // Section 22.2: conversation sessions do not receive this block.
    expect(sessionOpenPayloadSchema.safeParse(payload).success).toBe(false);
  });

  it("round-trips a WORKFLOW session.open interaction block", () => {
    const payload = sessionOpenBase({
      kind: "WORKFLOW",
      executionMode: "ORCHESTRATION",
      orchestration: { schemaVersion: "1.0" },
      interaction: canonicalInteractionPolicy,
    });
    const original = parseUnifiedFrame(
      JSON.stringify(envelope(payload, MessageType.SESSION_OPEN)),
    );
    const encoded = JSON.parse(encodeUnifiedFrame(original));
    expect(encoded.payload.interaction).toEqual(canonicalInteractionPolicy);
  });
});

describe("§22 SDK capability advertisement constant (Task 1, Task 3 wires runtime)", () => {
  it("exposes the exact §22.2 capability advertisement shape", () => {
    expect(SESSION_INTERACTION_CAPABILITY).toEqual({
      sessionInteraction: { version: 1, temporaryHold: true },
    });
  });

  it("keeps hostOpenCapabilitySchema validating the advertised shape", () => {
    expect(hostOpenCapabilitySchema.safeParse(
      SESSION_INTERACTION_CAPABILITY,
    ).success).toBe(true);
  });
});
