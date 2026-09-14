// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Unified host-control protocol frame schemas (§4.3–§14).
 *
 * Mirrored byte-for-byte from the engine's committed Java records in
 * `control/engine/engine-core/src/main/java/ai/myrmec/engine/websocket/host/`.
 * The envelope is validated first; the payload is narrowed by `type` via
 * `parseUnifiedFrame`. Required fields are required; optional/nullable fields
 * are marked per the Java record comments.
 */

import { z } from "zod";

const uuid = z.string().uuid();
const isoString = z.string().datetime({ offset: true });
const mapUnknown = z.record(z.string(), z.unknown()).default({});

// ---- Protocol constants (mirrors HostProtocol.java) ----

export const SUPPORTED_PROTOCOL_VERSION = 1;

export const MessageType = {
  HOST_OPEN: "host.open",
  HOST_OPENED: "host.opened",
  HOST_HEARTBEAT: "host.heartbeat",
  HOST_CAPACITY: "host.capacity",

  SESSION_OFFER: "session.offer",
  SESSION_ACCEPT: "session.accept",
  SESSION_REJECT: "session.reject",
  SESSION_OPEN: "session.open",
  SESSION_OPENED: "session.opened",
  SESSION_CLOSE: "session.close",
  SESSION_CLOSED: "session.closed",

  EXECUTION_START: "execution.start",
  EXECUTION_ACCEPT: "execution.accept",
  EXECUTION_REJECT: "execution.reject",
  EXECUTION_DELTA: "execution.delta",
  EXECUTION_EVENT: "execution.event",
  EXECUTION_COMPLETE: "execution.complete",
  EXECUTION_FAILED: "execution.failed",
  EXECUTION_PAUSED: "execution.paused",
  EXECUTION_CANCEL: "execution.cancel",
  EXECUTION_CANCELLED: "execution.cancelled",
  EXECUTION_POLICY_UPDATE: "execution.policy.update",
  EXECUTION_APPROVAL_REQUESTED: "execution.approval.requested",

  PROTOCOL_ACK: "protocol.ack",
  PROTOCOL_ERROR: "protocol.error",
} as const;
export type UnifiedMessageType =
  (typeof MessageType)[keyof typeof MessageType];

// §14 protocol error codes.
export const ProtocolErrorCode = {
  INVALID_MESSAGE: "INVALID_MESSAGE",
  UNSUPPORTED_VERSION: "UNSUPPORTED_VERSION",
  UNSUPPORTED_MESSAGE: "UNSUPPORTED_MESSAGE",
  INVALID_STATE: "INVALID_STATE",
  IDENTITY_MISMATCH: "IDENTITY_MISMATCH",
  EVENT_BACKPRESSURE_TIMEOUT: "EVENT_BACKPRESSURE_TIMEOUT",
  SESSION_NOT_FOUND: "SESSION_NOT_FOUND",
  EXECUTION_NOT_FOUND: "EXECUTION_NOT_FOUND",
} as const;
export type ProtocolErrorCodeType =
  (typeof ProtocolErrorCode)[keyof typeof ProtocolErrorCode];

// ---- Envelope (mirrors HostProtocolEnvelope.java) ----

export const unifiedEnvelopeSchema = z
  .object({
    protocolVersion: z.literal(SUPPORTED_PROTOCOL_VERSION),
    messageId: z.string().min(1),
    type: z.string().min(1),
    sentAt: isoString,
    correlationId: z.string().nullish(),
    hostInstanceId: uuid.nullish(),
    sessionId: uuid.nullish(),
    executionId: uuid.nullish(),
    sequence: z.number().int().nullable().default(null),
    payload: z.unknown(),
  })
  .passthrough();
export type UnifiedEnvelope = z.infer<typeof unifiedEnvelopeSchema>;

// ---- Host lifecycle payloads (§6) ----

export const hostOpenPayloadSchema = z.object({
  instanceNonce: uuid,
  hostname: z.string(),
  runtimeVersion: z.string(),
  supportedProtocolVersions: z.array(z.number().int()),
  poolSize: z.number().int(),
  capabilities: mapUnknown,
  reportedCapacity: mapUnknown,
});
export type HostOpenPayload = z.infer<typeof hostOpenPayloadSchema>;

export const streamLimitsSchema = z.object({
  maxFrameBytes: z.number().int(),
  maxBufferedDeltaBytesPerSession: z.number().int(),
  maxUnacknowledgedEventBytesPerSession: z.number().int(),
  eventBackpressureTimeoutSeconds: z.number().int(),
});
export type StreamLimits = z.infer<typeof streamLimitsSchema>;

export const hostOpenedPayloadSchema = z.object({
  hostInstanceId: uuid,
  protocolVersion: z.number().int(),
  effectivePoolSize: z.number().int(),
  heartbeatIntervalSeconds: z.number().int(),
  offerTimeoutSeconds: z.number().int(),
  eventReplayWindowSeconds: z.number().int(),
  streamLimits: streamLimitsSchema,
  serverNodeId: z.string(),
});
export type HostOpenedPayload = z.infer<typeof hostOpenedPayloadSchema>;

export const hostHeartbeatPayloadSchema = z.object({
  observedAt: isoString,
  effectivePoolSize: z.number().int(),
  activeSessionCount: z.number().int(),
  pendingOfferCount: z.number().int(),
  health: z.string(),
});
export type HostHeartbeatPayload = z.infer<typeof hostHeartbeatPayloadSchema>;

export const hostCapacityPayloadSchema = z.object({
  poolSize: z.number().int(),
  reason: z.string(),
  reportedCapacity: mapUnknown,
});
export type HostCapacityPayload = z.infer<typeof hostCapacityPayloadSchema>;

// ---- Session allocation payloads (§7) ----

export const sessionOfferPayloadSchema = z.object({
  allocationId: uuid,
  sessionId: uuid,
  kind: z.string(),
  ref: z.object({ type: z.string(), id: uuid }),
  requirements: z.object({
    tools: z.array(z.string()),
    runtimes: z.array(z.string()),
    features: z.array(z.string()),
  }),
  lease: z.object({
    offerExpiresAt: isoString,
    idleTimeoutSeconds: z.number().int(),
  }),
  routing: z.object({
    homeNodeId: z.string(),
    homeNodeAddress: z.string().nullish(),
  }),
});
export type SessionOfferPayload = z.infer<typeof sessionOfferPayloadSchema>;

export const sessionAcceptPayloadSchema = z.object({
  allocationId: uuid,
  sessionId: uuid,
  acceptedAt: isoString,
});
export type SessionAcceptPayload = z.infer<typeof sessionAcceptPayloadSchema>;

export const sessionRejectPayloadSchema = z.object({
  allocationId: uuid,
  sessionId: uuid,
  reasonCode: z.string(),
  message: z.string(),
  retryable: z.boolean(),
});
export type SessionRejectPayload = z.infer<typeof sessionRejectPayloadSchema>;

export const sessionOpenedPayloadSchema = z.object({
  sessionId: uuid,
  ready: z.boolean(),
  channelMode: z.string(),
});
export type SessionOpenedPayload = z.infer<typeof sessionOpenedPayloadSchema>;

export const sessionClosedPayloadSchema = z.object({
  sessionId: uuid,
  closedAt: isoString,
  reasonCode: z.string(),
});
export type SessionClosedPayload = z.infer<typeof sessionClosedPayloadSchema>;

// Engine→host session.close is sent as a raw Map in the engine.
export const sessionClosePayloadSchema = z.object({
  sessionId: uuid,
  reasonCode: z.string(),
  gracePeriodSeconds: z.number().int(),
});
export type SessionClosePayload = z.infer<typeof sessionClosePayloadSchema>;

// ---- Execution lifecycle payloads (§8) ----

export const inferenceMessageSchema = z.object({
  role: z.string(),
  content: z.string().nullish(),
  parts: z
    .array(
      z.object({
        type: z.string(),
        text: z.string().nullish(),
        attachmentId: z.string().nullish(),
        mediaType: z.string().nullish(),
        readContentPath: z.string().nullish(),
      }),
    )
    .nullish(),
  toolCalls: z
    .array(
      z.object({
        id: z.string(),
        name: z.string(),
        args: z.record(z.string(), z.unknown()).default({}),
      }),
    )
    .nullish(),
});
export type InferenceMessage = z.infer<typeof inferenceMessageSchema>;

export const modelConfigSchema = z.object({
  provider: z.string(),
  modelId: z.string(),
  apiEndpoint: z.string().nullish(),
  apiKey: z.string().nullish(),
  parameters: mapUnknown,
});
export type ModelConfig = z.infer<typeof modelConfigSchema>;

export const workspaceConfigSchema = z.object({
  repoUrl: z.string(),
  branch: z.string(),
  subPath: z.string().nullish(),
  repoToken: z.string().nullish(),
});
export type WorkspaceConfig = z.infer<typeof workspaceConfigSchema>;

export const toolDefinitionSchema = z.object({
  name: z.string(),
  description: z.string(),
  parameters: mapUnknown,
  riskClass: z.string(),
});
export type ToolDefinition = z.infer<typeof toolDefinitionSchema>;

export const knowledgeSourceHandleSchema = z.object({
  knowledgeSourceId: uuid,
  name: z.string(),
  description: z.string(),
});
export type KnowledgeSourceHandle = z.infer<typeof knowledgeSourceHandleSchema>;

export const sessionOpenPayloadSchema = z.object({
  sessionId: uuid,
  serviceType: z.string(),
  projectId: uuid,
  profileVersionId: uuid,
  model: modelConfigSchema,
  workspace: workspaceConfigSchema.nullish(),
  tools: z.array(toolDefinitionSchema),
  knowledgeSources: z.array(knowledgeSourceHandleSchema),
  autoHitlOnDestructive: z.boolean(),
});
export type SessionOpenPayload = z.infer<typeof sessionOpenPayloadSchema>;

export const executionStartPayloadSchema = z.object({
  executionId: uuid,
  sessionId: uuid,
  sequenceNo: z.number().int().nullish(),
  requestId: z.string().nullish(),
  deadline: isoString,
  input: z
    .object({
      messages: z.array(inferenceMessageSchema),
      attachments: z
        .array(
          z.object({
            id: z.string(),
            name: z.string(),
            contentType: z.string(),
            uri: z.string(),
          }),
        )
        .nullish(),
      conversationContinuation: z
        .object({
          approvalRequestId: z.string().nullish(),
          decisionId: z.string().nullish(),
          pendingActionId: z.string().nullish(),
          pendingActionDigest: z.string().nullish(),
        })
        .nullish(),
    })
    .nullish(),
  toolPolicy: z
    .object({
      activeToolNames: z.array(z.string()).nullish(),
      approvalMode: z.string().nullish(),
    })
    .nullish(),
  output: z
    .object({
      stream: z.boolean(),
      responseSequenceNo: z.number().int().nullish(),
      format: z.string().nullish(),
    })
    .nullish(),
});
export type ExecutionStartPayload = z.infer<typeof executionStartPayloadSchema>;

export const orchestrationExecutionStartPayloadSchema = z.object({
  executionId: uuid,
  sessionId: uuid,
  dispatchId: uuid,
  attemptId: uuid,
  assignmentDigest: z.string(),
  deadline: isoString,
});
export type OrchestrationExecutionStartPayload = z.infer<
  typeof orchestrationExecutionStartPayloadSchema
>;

export const executionAcceptPayloadSchema = z.object({
  executionId: uuid,
  startedAt: isoString,
  resolvedModelId: z.string(),
  dispatchId: uuid,
  assignmentDigest: z.string(),
});
export type ExecutionAcceptPayload = z.infer<typeof executionAcceptPayloadSchema>;

export const executionRejectPayloadSchema = z.object({
  executionId: uuid,
  reasonCode: z.string(),
  message: z.string(),
  retryable: z.boolean(),
});
export type ExecutionRejectPayload = z.infer<typeof executionRejectPayloadSchema>;

export const executionDeltaPayloadSchema = z.object({
  executionId: uuid,
  index: z.number().int(),
  content: z.string(),
  contentType: z.string(),
});
export type ExecutionDeltaPayload = z.infer<typeof executionDeltaPayloadSchema>;

export const executionEventPayloadSchema = z.object({
  executionId: uuid,
  eventId: uuid,
  eventType: z.string(),
  occurredAt: isoString,
  data: mapUnknown,
});
export type ExecutionEventPayload = z.infer<typeof executionEventPayloadSchema>;

export const executionCompletePayloadSchema = z.object({
  executionId: uuid,
  completedAt: isoString,
  result: z
    .object({
      content: z.string().nullish(),
      structured: z.record(z.string(), z.unknown()).nullish(),
      artifacts: z.array(z.string()).nullish(),
    })
    .nullish(),
  usage: z
    .object({
      modelId: z.string().nullish(),
      inputTokens: z.number().int().nullish(),
      outputTokens: z.number().int().nullish(),
      durationMs: z.number().int().nullish(),
    })
    .nullish(),
});
export type ExecutionCompletePayload = z.infer<
  typeof executionCompletePayloadSchema
>;

export const executionFailedPayloadSchema = z.object({
  executionId: uuid,
  failedAt: isoString,
  error: z.object({
    code: z.string(),
    message: z.string(),
    category: z.string().nullish(),
    retryable: z.boolean(),
    retryAfterSeconds: z.number().int().nullish(),
  }),
  usage: z
    .object({
      modelId: z.string().nullish(),
      inputTokens: z.number().int().nullish(),
      outputTokens: z.number().int().nullish(),
      durationMs: z.number().int().nullish(),
    })
    .nullish(),
});
export type ExecutionFailedPayload = z.infer<typeof executionFailedPayloadSchema>;

export const executionPausedPayloadSchema = z.object({
  executionId: uuid,
  pausedAt: isoString,
  reasonCode: z.string(),
  continuation: z
    .object({
      continuationId: z.string().nullish(),
      continuationRef: z.string().nullish(),
      recoveryProviderId: z.string().nullish(),
      portability: z.string().nullish(),
      snapshotRef: z.string().nullish(),
      snapshotDigest: z.string().nullish(),
      snapshotTreeHash: z.string().nullish(),
      workspaceRevision: z.number().int().nullish(),
      stateDigest: z.string().nullish(),
    })
    .nullish(),
  suspension: z
    .object({
      approvalRequestId: z.string().nullish(),
      pendingAction: z
        .object({
          actionId: z.string(),
          type: z.string(),
          riskClass: z.string(),
          summary: z.string(),
          digest: z.string(),
        })
        .nullish(),
      expiresAt: isoString.nullish(),
    })
    .nullish(),
  conversationContinuation: z
    .object({
      approvalRequestId: z.string().nullish(),
      pendingActionId: z.string().nullish(),
      pendingActionDigest: z.string().nullish(),
    })
    .nullish(),
  usage: z
    .object({
      modelId: z.string().nullish(),
      inputTokens: z.number().int().nullish(),
      outputTokens: z.number().int().nullish(),
    })
    .nullish(),
});
export type ExecutionPausedPayload = z.infer<typeof executionPausedPayloadSchema>;

export const executionCancelPayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid,
  reasonCode: z.string(),
  requestedAt: isoString,
  gracePeriodSeconds: z.number().int(),
});
export type ExecutionCancelPayload = z.infer<typeof executionCancelPayloadSchema>;

export const executionCancelledPayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid,
  cancelledAt: isoString,
  reasonCode: z.string(),
});
export type ExecutionCancelledPayload = z.infer<
  typeof executionCancelledPayloadSchema
>;

export const executionApprovalRequestedPayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid.nullish(),
  approvalRequestId: z.string(),
  action: z
    .object({
      actionId: z.string(),
      type: z.string(),
      riskClass: z.string(),
      summary: z.string(),
      digest: z.string(),
    })
    .nullish(),
  snapshotTreeHash: z.string().nullish(),
  stateDigest: z.string().nullish(),
  expiresAt: isoString,
});
export type ExecutionApprovalRequestedPayload = z.infer<
  typeof executionApprovalRequestedPayloadSchema
>;

// ---- Protocol housekeeping payloads ----

export const protocolAckPayloadSchema = z.object({
  acknowledgedMessageId: z.string(),
  highestContiguousSequence: z.number().int(),
  status: z.literal("DURABLY_RECORDED"),
});
export type ProtocolAckPayload = z.infer<typeof protocolAckPayloadSchema>;

const protocolErrorCodeSchema = z.enum([
  "INVALID_MESSAGE",
  "UNSUPPORTED_VERSION",
  "UNSUPPORTED_MESSAGE",
  "INVALID_STATE",
  "IDENTITY_MISMATCH",
  "EVENT_BACKPRESSURE_TIMEOUT",
  "SESSION_NOT_FOUND",
  "EXECUTION_NOT_FOUND",
]);

export const protocolErrorPayloadSchema = z.object({
  code: protocolErrorCodeSchema,
  message: z.string(),
  retryable: z.boolean(),
  offendingMessageId: z.string().nullish(),
  scope: z.string(),
  details: z.record(z.string(), z.unknown()).nullish(),
});
export type ProtocolErrorPayload = z.infer<typeof protocolErrorPayloadSchema>;

// ---- Typed envelope unions ----

export type UnifiedFrame<T extends UnifiedMessageType = UnifiedMessageType> =
  UnifiedEnvelope & { type: T };

export type HostOpenFrame = UnifiedFrame<"host.open"> & {
  payload: HostOpenPayload;
};
export type HostOpenedFrame = UnifiedFrame<"host.opened"> & {
  payload: HostOpenedPayload;
};
export type HostHeartbeatFrame = UnifiedFrame<"host.heartbeat"> & {
  payload: HostHeartbeatPayload;
};
export type HostCapacityFrame = UnifiedFrame<"host.capacity"> & {
  payload: HostCapacityPayload;
};

export type SessionOfferFrame = UnifiedFrame<"session.offer"> & {
  payload: SessionOfferPayload;
};
export type SessionAcceptFrame = UnifiedFrame<"session.accept"> & {
  payload: SessionAcceptPayload;
};
export type SessionRejectFrame = UnifiedFrame<"session.reject"> & {
  payload: SessionRejectPayload;
};
export type SessionOpenFrame = UnifiedFrame<"session.open"> & {
  payload: SessionOpenPayload;
};
export type SessionOpenedFrame = UnifiedFrame<"session.opened"> & {
  payload: SessionOpenedPayload;
};
export type SessionCloseFrame = UnifiedFrame<"session.close"> & {
  payload: SessionClosePayload;
};
export type SessionClosedFrame = UnifiedFrame<"session.closed"> & {
  payload: SessionClosedPayload;
};

export type ExecutionStartFrame = UnifiedFrame<"execution.start"> & {
  payload: ExecutionStartPayload | OrchestrationExecutionStartPayload;
};
export type ExecutionAcceptFrame = UnifiedFrame<"execution.accept"> & {
  payload: ExecutionAcceptPayload;
};
export type ExecutionRejectFrame = UnifiedFrame<"execution.reject"> & {
  payload: ExecutionRejectPayload;
};
export type ExecutionDeltaFrame = UnifiedFrame<"execution.delta"> & {
  payload: ExecutionDeltaPayload;
};
export type ExecutionEventFrame = UnifiedFrame<"execution.event"> & {
  payload: ExecutionEventPayload;
};
export type ExecutionCompleteFrame = UnifiedFrame<"execution.complete"> & {
  payload: ExecutionCompletePayload;
};
export type ExecutionFailedFrame = UnifiedFrame<"execution.failed"> & {
  payload: ExecutionFailedPayload;
};
export type ExecutionPausedFrame = UnifiedFrame<"execution.paused"> & {
  payload: ExecutionPausedPayload;
};
export type ExecutionCancelFrame = UnifiedFrame<"execution.cancel"> & {
  payload: ExecutionCancelPayload;
};
export type ExecutionCancelledFrame = UnifiedFrame<"execution.cancelled"> & {
  payload: ExecutionCancelledPayload;
};
export type ExecutionApprovalRequestedFrame =
  UnifiedFrame<"execution.approval.requested"> & {
    payload: ExecutionApprovalRequestedPayload;
  };

export type ProtocolAckFrame = UnifiedFrame<"protocol.ack"> & {
  payload: ProtocolAckPayload;
};
export type ProtocolErrorFrame = UnifiedFrame<"protocol.error"> & {
  payload: ProtocolErrorPayload;
};

// ---- Parse / encode helpers ----

/** Payload-schema dispatch map consumed by Task 2's client. */
export const unifiedPayloadSchemas: Record<
  UnifiedMessageType,
  z.ZodType<unknown>
> = {
  [MessageType.HOST_OPEN]: hostOpenPayloadSchema,
  [MessageType.HOST_OPENED]: hostOpenedPayloadSchema,
  [MessageType.HOST_HEARTBEAT]: hostHeartbeatPayloadSchema,
  [MessageType.HOST_CAPACITY]: hostCapacityPayloadSchema,

  [MessageType.SESSION_OFFER]: sessionOfferPayloadSchema,
  [MessageType.SESSION_ACCEPT]: sessionAcceptPayloadSchema,
  [MessageType.SESSION_REJECT]: sessionRejectPayloadSchema,
  [MessageType.SESSION_OPEN]: sessionOpenPayloadSchema,
  [MessageType.SESSION_OPENED]: sessionOpenedPayloadSchema,
  [MessageType.SESSION_CLOSE]: sessionClosePayloadSchema,
  [MessageType.SESSION_CLOSED]: sessionClosedPayloadSchema,

  [MessageType.EXECUTION_START]: z.union([
    orchestrationExecutionStartPayloadSchema,
    executionStartPayloadSchema,
  ]) as z.ZodType<unknown>,
  [MessageType.EXECUTION_ACCEPT]: executionAcceptPayloadSchema,
  [MessageType.EXECUTION_REJECT]: executionRejectPayloadSchema,
  [MessageType.EXECUTION_DELTA]: executionDeltaPayloadSchema,
  [MessageType.EXECUTION_EVENT]: executionEventPayloadSchema,
  [MessageType.EXECUTION_COMPLETE]: executionCompletePayloadSchema,
  [MessageType.EXECUTION_FAILED]: executionFailedPayloadSchema,
  [MessageType.EXECUTION_PAUSED]: executionPausedPayloadSchema,
  [MessageType.EXECUTION_CANCEL]: executionCancelPayloadSchema,
  [MessageType.EXECUTION_CANCELLED]: executionCancelledPayloadSchema,
  [MessageType.EXECUTION_POLICY_UPDATE]: z.record(z.string(), z.unknown()),
  [MessageType.EXECUTION_APPROVAL_REQUESTED]: executionApprovalRequestedPayloadSchema,

  [MessageType.PROTOCOL_ACK]: protocolAckPayloadSchema,
  [MessageType.PROTOCOL_ERROR]: protocolErrorPayloadSchema,
};

export type ParsedUnifiedFrame =
  | HostOpenFrame
  | HostOpenedFrame
  | HostHeartbeatFrame
  | HostCapacityFrame
  | SessionOfferFrame
  | SessionAcceptFrame
  | SessionRejectFrame
  | SessionOpenFrame
  | SessionOpenedFrame
  | SessionCloseFrame
  | SessionClosedFrame
  | ExecutionStartFrame
  | ExecutionAcceptFrame
  | ExecutionRejectFrame
  | ExecutionDeltaFrame
  | ExecutionEventFrame
  | ExecutionCompleteFrame
  | ExecutionFailedFrame
  | ExecutionPausedFrame
  | ExecutionCancelFrame
  | ExecutionCancelledFrame
  | ExecutionApprovalRequestedFrame
  | ProtocolAckFrame
  | ProtocolErrorFrame;

/**
 * Parse a raw host-control wire frame: validate the envelope shape, then
 * narrow the payload by `type`. Mirrors the engine's
 * `HostProtocolEnvelope.parse` + `treeToValue` pattern.
 */
export function parseUnifiedFrame(raw: string): ParsedUnifiedFrame {
  const parsed = JSON.parse(raw);
  const envelope = unifiedEnvelopeSchema.parse(parsed);
  const schema = unifiedPayloadSchemas[envelope.type as UnifiedMessageType];
  if (!schema) {
    throw new z.ZodError([
      {
        code: "custom",
        message: `Unsupported message type: ${envelope.type}`,
        path: ["type"],
      },
    ]);
  }
  const payload = schema.parse(envelope.payload);
  return { ...envelope, payload } as ParsedUnifiedFrame;
}

/** Encode a typed unified frame back to its wire JSON. */
export function encodeUnifiedFrame(frame: ParsedUnifiedFrame): string {
  return JSON.stringify(frame);
}
