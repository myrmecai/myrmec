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
  HOST_RESUME: "host.resume",
  HOST_RECONCILE: "host.reconcile",

  SESSION_OFFER: "session.offer",
  SESSION_ACCEPT: "session.accept",
  SESSION_REJECT: "session.reject",
  SESSION_OPEN: "session.open",
  SESSION_OPENED: "session.opened",
  SESSION_CLOSE: "session.close",
  SESSION_CLOSED: "session.closed",

  CHANNEL_OPEN: "channel.open",
  CHANNEL_OPENED: "channel.opened",

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

  // §22 session interaction + temporary hold (dedicated Agent Channel; §22.3).
  EXECUTION_CONTROL: "execution.control",
  EXECUTION_CONTROL_STATE: "execution.control.state",
  EXECUTION_INTERACTION: "execution.interaction",
  EXECUTION_INTERACTION_DELTA: "execution.interaction.delta",
  EXECUTION_INTERACTION_COMPLETE: "execution.interaction.complete",
  EXECUTION_INTERACTION_FAILED: "execution.interaction.failed",
  EXECUTION_CONTROL_REQUEST: "execution.control.request",
  EXECUTION_CONTROL_REQUEST_RESOLVED: "execution.control.request.resolved",

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
    sequence: z.number().int().nullish(),
    payload: z.unknown(),
  })
  .passthrough();
export type UnifiedEnvelope = z.infer<typeof unifiedEnvelopeSchema>;

// ---- Host lifecycle payloads (§6) ----

// ---- §22.2 session-interaction capability (current-contract, fail-closed) ----

/** The typed sessionInteraction capability block (§22.2). Version 1 +
 * temporaryHold=true is the ONLY accepted shape — no old-peer fallback. */
export const sessionInteractionCapabilitySchema = z.object({
  version: z.literal(1),
  temporaryHold: z.literal(true),
});
export type SessionInteractionCapability = z.infer<
  typeof sessionInteractionCapabilitySchema
>;

/** The capabilities map on host.open/host.opened: sessionInteraction is
 * REQUIRED (current-contract validation); other keys pass through. */
export const hostOpenCapabilitySchema = z.object({
  sessionInteraction: sessionInteractionCapabilitySchema,
}).passthrough();

/** §22.2: the SDK's mandatory capability advertisement. The client stamps
 * this onto every host.open; the engine echoes it in acceptedCapabilities. */
export const SESSION_INTERACTION_CAPABILITY = {
  sessionInteraction: { version: 1, temporaryHold: true },
} as const satisfies Record<string, unknown>;

export const hostOpenPayloadSchema = z.object({
  instanceNonce: uuid,
  hostname: z.string(),
  runtimeVersion: z.string(),
  supportedProtocolVersions: z.array(z.number().int()),
  poolSize: z.number().int(),
  // §22.2: sessionInteraction is REQUIRED — current-contract validation has
  // no old-host fallback (engine rejects missing/unsupported at host.open).
  capabilities: hostOpenCapabilitySchema,
  reportedCapacity: mapUnknown,
  /**
   * Local-owner model (§3.7/§4.1, additive): the id of the user logged into
   * the VS Code plugin whose local workspace opened the instance. Required
   * (non-null) for LOCAL hosts, MUST be absent for MANAGED hosts — the
   * HOST_JWT carries no user identity, so this field is the only owner
   * channel. Mirrors the engine's HostOpenPayload.ownerUserId.
   */
  ownerUserId: uuid.nullish(),
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
  // Per-instance PSK delivery (design §6, additive): 32-byte base64 key +
  // its id. Key material — frame-logger denylist on both sides; the SDK
  // holds it in process memory only.
  psk: z.string().nullish(),
  pskKeyId: z.string().nullish(),
  // §22.2: the engine's acceptedCapabilities echo (required — the engine
  // only opens capable hosts).
  acceptedCapabilities: hostOpenCapabilitySchema,
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

// ---- Reconnect & reconciliation payloads (§13, A2) ----

/**
 * §22.8 (Task 10): the optional interactionState block one reported session
 * carries on host.resume — the §22.2 live runtime's coordinator snapshot
 * (observed evidence, never permission). Fields mirror the engine's
 * 035 observed-state projection. Reported ONLY while a live interaction
 * runtime exists for the session; a session without one omits the block.
 */
export const reportedInteractionStateSchema = z.object({
  executionId: uuid,
  acceptedControlRevision: z.number().int().min(0),
  stateSequence: z.number().int().min(0),
  effectiveState: z.enum(["RUNNING", "HOLD_REQUESTED", "HELD"]),
  // The §14.4 armed resume instant (observation — the engine's timer
  // authority is separate); null when the clock was not armed.
  idleResumeAt: isoString.nullish(),
  pendingInteractionId: uuid.nullish(),
  pendingControlRequestIds: z.array(uuid).default([]),
});
export type ReportedInteractionState = z.infer<
  typeof reportedInteractionStateSchema
>;

/**
 * One session the host retained across a control-socket drop (§13). Fields
 * mirror the engine's `HostResumePayload.RetainedSession` record:
 * `capacityHeld` is the reconciliation assertion (the engine may KEEP only
 * when true), `lastSentSequence` the host's durable-event cursor, and
 * `lastAcknowledgedMessageId` the last terminal/session frame the host
 * acknowledged (terminal-resend dedup, §12.1). `interactionState` is the
 * §22.8 optional overlay evidence of the session's live interaction runtime
 * (Task 10).
 */
export const hostRetainedSessionSchema = z.object({
  sessionId: uuid,
  state: z.string(),
  capacityHeld: z.boolean(),
  activeExecutionId: uuid.nullish(),
  lastSentSequence: z.number().int().nullish(),
  lastAcknowledgedMessageId: z.string().nullish(),
  // §22.8 (Task 10): present only when the host holds a live interaction
  // runtime for the session; absent (dropped by JSON.stringify) otherwise.
  interactionState: reportedInteractionStateSchema.nullish(),
});
export type HostRetainedSession = z.infer<typeof hostRetainedSessionSchema>;

/** host.resume (host→engine): report retained sessions after reconnect. */
export const hostResumePayloadSchema = z.object({
  previousHostInstanceId: uuid,
  instanceNonce: uuid,
  sessions: z.array(hostRetainedSessionSchema).default([]),
});
export type HostResumePayload = z.infer<typeof hostResumePayloadSchema>;

/** §13 reconcile actions — allowed values for host.reconcile decisions. */
export const ReconcileAction = {
  KEEP: "KEEP",
  CANCEL_EXECUTION: "CANCEL_EXECUTION",
  CLOSE: "CLOSE",
} as const;
export type ReconcileActionType =
  (typeof ReconcileAction)[keyof typeof ReconcileAction];

/**
 * One per-session decision (§13). KEEP carries `resumeFromSequence` (the
 * replay cursor: durable events after this envelope sequence are replayed),
 * CANCEL_EXECUTION carries `executionId` (the decision IS the cancellation
 * command — no separate execution.cancel frame), CLOSE carries `reasonCode`.
 * Mirrors the engine's `HostResumePayload.ReconcilePayload.Decision`.
 */
export const reconcileDecisionSchema = z.object({
  sessionId: uuid,
  action: z.enum(["KEEP", "CANCEL_EXECUTION", "CLOSE"]),
  resumeFromSequence: z.number().int().nullish(),
  executionId: uuid.nullish(),
  reasonCode: z.string().nullish(),
});
export type ReconcileDecision = z.infer<typeof reconcileDecisionSchema>;

/** host.reconcile (engine→host): authoritative keep/cancel/close decisions. */
export const hostReconcilePayloadSchema = z.object({
  hostInstanceId: uuid,
  decisions: z.array(reconcileDecisionSchema).default([]),
});
export type HostReconcilePayload = z.infer<typeof hostReconcilePayloadSchema>;

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

// ---- Dedicated session channel payloads (§7.5, A1) ----

/** channel.open (host→engine, FIRST frame on the channel socket): bind the
 * dedicated transport to one session. `resumeFromSequence` is the host's
 * durable-event cursor — the replay resume point (§12.3). */
export const channelOpenPayloadSchema = z.object({
  sessionId: uuid,
  resumeFromSequence: z.number().int().nonnegative().default(0),
  // §15 rule 7: the single-use offer token rides the payload, NOT the auth
  // (the handshake is the same HOST_JWT gate as the control socket).
  token: z.string(),
});
export type ChannelOpenPayload = z.infer<typeof channelOpenPayloadSchema>;

/** channel.opened (engine→host): the channel is bound; the engine reports
 * its current highest contiguous sequence for the session. */
export const channelOpenedPayloadSchema = z.object({
  sessionId: uuid,
  highestContiguousSequence: z.number().int().nonnegative(),
});
export type ChannelOpenedPayload = z.infer<typeof channelOpenedPayloadSchema>;

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
  endpoint: z.string().nullish(),
  // Credential-envelope delivery (design §9): the plaintext key never rides
  // the wire; the model block references an entry in `credentials` by ref.
  credentialRef: z.string().nullish(),
  parameters: mapUnknown,
});
export type ModelConfig = z.infer<typeof modelConfigSchema>;

/**
 * LLM model info attached to a session (was `taskFrames.modelInfoSchema`,
 * retired with the legacy wire in P6-T6). Kept as its own export because
 * resolvers + orchestration parse engine descriptors through it and rely
 * on the `parameters` default.
 */
export const modelInfoSchema = z.object({
  provider: z.string(),
  modelId: z.string(),
  endpoint: z.string().nullish(),
  credentialRef: z.string().nullish(),
  parameters: z.record(z.string(), z.unknown()).default({}),
});
export type ModelInfoWire = z.infer<typeof modelInfoSchema>;

export const workspaceConfigSchema = z.object({
  repoUrl: z.string(),
  branch: z.string(),
  subPath: z.string().nullish(),
  // Credential-envelope delivery (design §9): was `repoToken` — the token
  // now travels only inside `credentials[].envelope`.
  credentialRef: z.string().nullish(),
});
export type WorkspaceConfig = z.infer<typeof workspaceConfigSchema>;

export const toolDefinitionSchema = z.object({
  name: z.string(),
  description: z.string(),
  inputSchema: mapUnknown,
  riskClass: z.string(),
});
export type ToolDefinition = z.infer<typeof toolDefinitionSchema>;

export const knowledgeSourceHandleSchema = z.object({
  id: uuid,
  name: z.string(),
  description: z.string(),
});
export type KnowledgeSourceHandle = z.infer<typeof knowledgeSourceHandleSchema>;

// ---- §7.3 (Wave 6, A4): policy + capture on session.open ----

/** §7.3: the host-enforced execution limits. Null fields = no host-side limit. */
export const sessionPolicySchema = z.object({
  maxIterations: z.number().int().positive().nullish(),
  executionTimeoutSeconds: z.number().int().positive().nullish(),
});
export type SessionPolicy = z.infer<typeof sessionPolicySchema>;

/** §7.3/§15 rule 12: what the host may emit on the event stream (sensitive capture). */
export const capturePolicySchema = z.object({
  level: z.string(),
  maxBytes: z.number().int().positive().nullish(),
});
export type CapturePolicy = z.infer<typeof capturePolicySchema>;

// ---- Credential envelopes (§7.3, design 2026-09-16-credential-envelope-
//      delivery.md §8/§9 — additive, mode-blind) ----

/**
 * `MyrmecSecureEnvelopeV1` — sealed AES-256-GCM credential. Secrets appear
 * ONLY inside this shape; envelope metadata is non-secret by construction.
 * Mirrors `MyrmecSecureEnvelope` in `security/credentialEnvelopes.ts`.
 */
export const credentialEnvelopeSchema = z.object({
  format: z.literal("MyrmecSecureEnvelopeV1"),
  keyId: z.string().min(1),
  sessionId: uuid,
  hostId: z.string().min(1),
  purpose: z.enum(["MODEL_PROVIDER", "WORKSPACE_TOKEN"]),
  createdAt: isoString,
  expiresAt: isoString,
  plaintextDigest: z.string().min(1),
  nonce: z.string().min(1),
  ciphertext: z.string().min(1),
});
export type CredentialEnvelope = z.infer<typeof credentialEnvelopeSchema>;

/** One delivered session credential: the ref configs reference + its envelope. */
export const sessionCredentialSchema = z.object({
  credentialRef: z.string().min(1),
  purpose: z.enum(["MODEL_PROVIDER", "WORKSPACE_TOKEN"]),
  envelope: credentialEnvelopeSchema,
});
export type SessionCredential = z.infer<typeof sessionCredentialSchema>;

// ---- §22 session interaction + temporary hold (Task 1 wire contract) ----
// Declared BEFORE sessionOpenPayloadSchema, which composes the interaction
// policy in its kind-discriminated superRefine.

/**
 * §22.2: the effective immutable interaction policy on orchestration
 * session.open payloads. Bounds are the tighten-only domain — the engine
 * validates before send; a frame outside these ranges fails parse.
 */
export const interactionPolicySchema = z.object({
  version: z.literal(1),
  enabled: z.boolean(),
  idleResumeAfterSeconds: z.number().int().min(30).max(3600),
  responseTimeoutSeconds: z.number().int().min(5).max(300),
  maxInputBytes: z.number().int().min(1).max(16384),
  maxOutputBytes: z.number().int().min(1).max(65536),
  maxModelIterations: z.number().int().min(1).max(8),
  maxHistoryBytes: z.number().int().min(1).max(262144),
  transcriptRetentionDays: z.number().int().min(1).max(30),
  contentMode: z.enum(["USER_CHAT_ONLY", "NONE"]),
});
export type InteractionPolicy = z.infer<typeof interactionPolicySchema>;

/** §22.4 execution.control (engine→host, durable command). HOLD requires
 * the holdPolicy block; CONTINUE omits it. */
export const executionControlPayloadSchema = z
  .object({
    executionId: uuid,
    dispatchId: uuid,
    controlRevision: z.number().int().min(1),
    action: z.enum(["HOLD", "CONTINUE"]),
    reasonCode: z.string().min(1),
    // §22.4: HOLD requires holdPolicy matching the session policy — the
    // §22.2 tighten-only idle domain (30..3600) applies to the command.
    holdPolicy: z
      .object({ idleResumeAfterSeconds: z.number().int().min(30).max(3600) })
      .nullish(),
    controlRequestId: uuid.nullish(),
  })
  .superRefine((value, ctx) => {
    // §22.4: HOLD requires holdPolicy matching the session policy; CONTINUE
    // omits it.
    if (value.action === "HOLD" && value.holdPolicy == null) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["holdPolicy"],
        message: "HOLD requires holdPolicy (§22.4)",
      });
    }
  });
export type ExecutionControlPayload = z.infer<
  typeof executionControlPayloadSchema
>;

/** §22.4 execution.control.state (host→engine, durable event). */
export const executionControlStatePayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid,
  controlRevision: z.number().int().min(0),
  stateSequence: z.number().int().min(1),
  status: z.enum(["HOLD_REQUESTED", "HELD", "RUNNING", "REJECTED"]),
  effectiveState: z.enum(["RUNNING", "HOLD_REQUESTED", "HELD"]),
  reasonCode: z.string().min(1),
  changedAt: isoString,
  // Present only when HELD with the idle clock armed; null otherwise.
  idleResumeAt: isoString.nullish(),
  // Present on HELD only.
  safePoint: z
    .enum([
      "BEFORE_MODEL_CALL",
      "BEFORE_TOOL_EXECUTION",
      "BEFORE_HELPER_CALL",
      "BEFORE_GIT_EFFECT",
    ])
    .nullish(),
  // A REJECTED state carries the refused command's revision.
  rejectedControlRevision: z.number().int().min(1).nullish(),
  errorCode: z.string().nullish(),
});
export type ExecutionControlStatePayload = z.infer<
  typeof executionControlStatePayloadSchema
>;

/** §22.6 execution.interaction (engine→host, durable command): one
 * authorized user message. Actor identity is engine-stamped. */
export const executionInteractionPayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid,
  interactionId: uuid,
  ordinal: z.number().int().min(1),
  actorUserId: uuid,
  message: z.object({ text: z.string().min(1) }),
  acceptedAt: isoString,
  responseDeadline: isoString,
});
export type ExecutionInteractionPayload = z.infer<
  typeof executionInteractionPayloadSchema
>;

/** §22.6 execution.interaction.delta (host→engine, ephemeral): best-effort
 * answer fragment. index starts at 0 per interaction; no replay promised. */
export const executionInteractionDeltaPayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid,
  interactionId: uuid,
  index: z.number().int().min(0),
  text: z.string().min(1),
});
export type ExecutionInteractionDeltaPayload = z.infer<
  typeof executionInteractionDeltaPayloadSchema
>;

/** §22.6 attributed chat usage; null tokens mean unknown. */
export const interactionUsageSchema = z.object({
  inputTokens: z.number().int().min(0).nullish(),
  outputTokens: z.number().int().min(0).nullish(),
  modelId: z.string().nullish(),
});
export type InteractionUsage = z.infer<typeof interactionUsageSchema>;

/** §22.6 execution.interaction.complete (host→engine, durable event): the
 * complete answer + usage; replaces provisional deltas. */
export const executionInteractionCompletePayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid,
  interactionId: uuid,
  ordinal: z.number().int().min(1),
  answer: z.object({ text: z.string().min(1) }),
  // KNOWN usage carries the block; UNKNOWN usage is null — never zero.
  usage: interactionUsageSchema.nullish(),
  usageStatus: z.enum(["KNOWN", "UNKNOWN"]),
  controlRequestIds: z.array(uuid),
  completedAt: isoString,
});
export type ExecutionInteractionCompletePayload = z.infer<
  typeof executionInteractionCompletePayloadSchema
>;

/** §22.6 the closed interaction-failure error-code catalogue. */
export const interactionErrorCodeSchema = z.enum([
  "INTERACTION_TIMEOUT",
  "MODEL_ERROR",
  "OUTPUT_LIMIT_EXCEEDED",
  "TOKEN_USAGE_UNAVAILABLE",
  "EXECUTION_TERMINAL",
  "EXECUTION_CANCELLED",
  "HOST_STATE_LOST",
  "CAPTURE_BLOCKED",
]);

/** §22.6 execution.interaction.failed (host→engine, durable event):
 * interaction-only failure + partial usage; does not fail the attempt. */
export const executionInteractionFailedPayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid,
  interactionId: uuid,
  ordinal: z.number().int().min(1),
  error: z.object({
    errorCode: interactionErrorCodeSchema,
    message: z.string().min(1),
    retryable: z.boolean(),
  }),
  usage: interactionUsageSchema.nullish(),
  usageStatus: z.enum(["KNOWN", "UNKNOWN"]),
  controlRequestIds: z.array(uuid),
  completedAt: isoString,
});
export type ExecutionInteractionFailedPayload = z.infer<
  typeof executionInteractionFailedPayloadSchema
>;

/** §22.7 execution.control.request (host→engine, durable event): the chat
 * tool's proposal for an engine-authorized control. The host cannot supply
 * an actor — the engine derives it from the persisted interaction. */
export const executionControlRequestPayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid,
  interactionId: uuid,
  controlRequestId: uuid,
  action: z.enum(["HOLD", "CONTINUE", "CANCEL"]),
  explanation: z.string().nullish(),
});
export type ExecutionControlRequestPayload = z.infer<
  typeof executionControlRequestPayloadSchema
>;

/** §22.7 execution.control.request.resolved (engine→host, durable command):
 * proposal disposition — NOT observed execution state. */
export const executionControlRequestResolvedPayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid,
  interactionId: uuid,
  controlRequestId: uuid,
  resolutionRevision: z.number().int().min(1),
  status: z.enum(["ACCEPTED", "CONFIRMATION_REQUIRED", "REJECTED", "DECLINED", "EXPIRED"]),
  // CONFIRMATION_REQUIRED carries the engine-stamped expiry.
  expiresAt: isoString.nullish(),
  // ACCEPTED includes the persisted commandMessageId (dispatch committed).
  commandMessageId: z.string().min(1).nullish(),
  // ACCEPTED for HOLD/CONTINUE includes the engine's controlRevision.
  controlRevision: z.number().int().min(1).nullish(),
  // Refusal codes are the §22.7 catalogue (extensible entries ride later
  // protocol bumps — unknown codes fail parse on the current contract).
  errorCode: z
    .enum(["FORBIDDEN", "EXECUTION_TERMINAL", "INVALID_INTERACTION", "CONFIRMATION_EXPIRED"])
    .nullish(),
});
export type ExecutionControlRequestResolvedPayload = z.infer<
  typeof executionControlRequestResolvedPayloadSchema
>;

export const sessionOpenPayloadSchema = z.object({
  sessionId: uuid,
  kind: z.string(),
  projectId: uuid,
  profileVersionId: uuid,
  model: modelConfigSchema,
  workspace: workspaceConfigSchema.nullish(),
  tools: z.array(toolDefinitionSchema),
  knowledgeSources: z.array(knowledgeSourceHandleSchema),
  autoHitlOnDestructive: z.boolean(),
  // §7.3 (§21.4): the dispatch context — executionMode is "ORCHESTRATION"
  // on orchestration sessions, null for conversations; ref is the
  // orchestration external reference (null when the engine carries none).
  executionMode: z.string().nullish(),
  ref: z.string().nullish(),
  // §16.2/§16.3 (P6-T6): an ORCHESTRATOR step's complete self-contained
  // assignment rides session.open as `orchestration` + its sha-256
  // `assignmentDigest` — the assignment IS that session's context.
  orchestration: z.record(z.string(), z.unknown()).nullish(),
  assignmentDigest: z.string().nullish(),
  // Credential envelopes (design §9): omitted when the session is keyless.
  credentials: z.array(sessionCredentialSchema).optional(),
  // §7.5 (A1): the dedicated-transport offer — endpoint + single-use
  // short-lived token. Null/absent when the engine disabled the channel
  // feature (`myrmec.channel.enabled`) or could not mint an offer.
  channel: z
    .object({
      endpoint: z.string(),
      token: z.string(),
    })
    .nullish(),
  // §7.3 (Wave 6, A4): the host-enforced execution limits. Null/absent
  // fields mean "no limit on the host".
  policy: sessionPolicySchema.nullish(),
  // §7.3/§15 rule 12 (Wave 6): the sensitive-capture policy — METADATA is
  // the platform default (tool arguments/results/prompts stay off the
  // event stream, bounded metadata only).
  capture: capturePolicySchema.nullish(),
  // §22.2: the effective immutable interaction policy — REQUIRED sibling of
  // `assignment` on WORKFLOW-kind sessions, ABSENT on CONVERSATION ones
  // (their execution behavior is unchanged). Fail-closed by kind via the
  // chained superRefine below.
  interaction: interactionPolicySchema.nullish(),
})
  // §22.2 kind discrimination: WORKFLOW requires the block, CONVERSATION
  // must not carry it.
  .superRefine((value, ctx) => {
    if (value.kind === "WORKFLOW" && value.interaction == null) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["interaction"],
        message:
          "WORKFLOW session.open requires the interaction policy block (§22.2)",
      });
    }
    if (value.kind !== "WORKFLOW" && value.interaction != null) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["interaction"],
        message:
          "only WORKFLOW-kind sessions carry the interaction policy block (§22.2)",
      });
    }
  });
export type SessionChannelOffer = NonNullable<
  NonNullable<SessionOpenPayload["channel"]>
>;
/** The kind-checked session.open schema used by the dispatcher (§22.2). */
export type SessionOpenPayloadKindChecked = z.infer<
  typeof sessionOpenPayloadSchema
>;
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
  // Protocol 8.6: an orchestration pause carries the function-call/token
  // totals; a conversation pause carries model/token accounting. One
  // permissive block accepts either variant (the emitter sends only the
  // keys its session kind carries).
  usage: z
    .object({
      modelId: z.string().nullish(),
      inputTokens: z.number().int().nullish(),
      outputTokens: z.number().int().nullish(),
      orchestrationFunctionCalls: z.number().int().nullish(),
      totalTokens: z.number().int().nullish(),
    })
    .nullish(),
});
export type ExecutionPausedPayload = z.infer<typeof executionPausedPayloadSchema>;

export const executionCancelPayloadSchema = z.object({
  executionId: uuid,
  // Protocol 8.8: dispatchId identifies an ORCHESTRATION execution's
  // dispatch; a conversation turn's cancel carries none. Nullable so a
  // null-dispatchId frame never fails schema validation (a dropped cancel
  // frame leaves the runner to budget-terminate instead of unwinding).
  dispatchId: uuid.nullish(),
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

// ---- §8.7 (A4): execution.policy.update (engine→host tighten-only) ----

/**
 * §8.7: the engine's durably accounted usage + tighten-only allowance.
 * `usage` is the engine's accounted value (the host rejects a backward
 * roll against its local monotonic accounting); `allowance.maxTokens` may
 * only preserve or tighten the current limit.
 */
export const executionPolicyUpdatePayloadSchema = z.object({
  executionId: uuid,
  dispatchId: uuid.nullish(),
  usage: z.object({
    orchestrationFunctionCalls: z.number().int().nonnegative(),
    totalTokens: z.number().int().nonnegative(),
  }),
  allowance: z
    .object({
      maxTokens: z.number().int().positive().nullish(),
    })
    .nullish(),
});
export type ExecutionPolicyUpdatePayload = z.infer<
  typeof executionPolicyUpdatePayloadSchema
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
export type HostResumeFrame = UnifiedFrame<"host.resume"> & {
  payload: HostResumePayload;
};
export type HostReconcileFrame = UnifiedFrame<"host.reconcile"> & {
  payload: HostReconcilePayload;
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

export type ChannelOpenFrame = UnifiedFrame<"channel.open"> & {
  payload: ChannelOpenPayload;
};
export type ChannelOpenedFrame = UnifiedFrame<"channel.opened"> & {
  payload: ChannelOpenedPayload;
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
export type ExecutionPolicyUpdateFrame =
  UnifiedFrame<"execution.policy.update"> & {
    payload: ExecutionPolicyUpdatePayload;
  };
export type ExecutionApprovalRequestedFrame =
  UnifiedFrame<"execution.approval.requested"> & {
    payload: ExecutionApprovalRequestedPayload;
  };

export type ExecutionControlFrame = UnifiedFrame<"execution.control"> & {
  payload: ExecutionControlPayload;
};
export type ExecutionControlStateFrame =
  UnifiedFrame<"execution.control.state"> & {
    payload: ExecutionControlStatePayload;
  };
export type ExecutionInteractionFrame =
  UnifiedFrame<"execution.interaction"> & {
    payload: ExecutionInteractionPayload;
  };
export type ExecutionInteractionDeltaFrame =
  UnifiedFrame<"execution.interaction.delta"> & {
    payload: ExecutionInteractionDeltaPayload;
  };
export type ExecutionInteractionCompleteFrame =
  UnifiedFrame<"execution.interaction.complete"> & {
    payload: ExecutionInteractionCompletePayload;
  };
export type ExecutionInteractionFailedFrame =
  UnifiedFrame<"execution.interaction.failed"> & {
    payload: ExecutionInteractionFailedPayload;
  };
export type ExecutionControlRequestFrame =
  UnifiedFrame<"execution.control.request"> & {
    payload: ExecutionControlRequestPayload;
  };
export type ExecutionControlRequestResolvedFrame =
  UnifiedFrame<"execution.control.request.resolved"> & {
    payload: ExecutionControlRequestResolvedPayload;
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
  // host.resume is host→engine only (never parsed inbound); host.reconcile
  // is the engine's reply the client parses.
  [MessageType.HOST_RESUME]: hostResumePayloadSchema,
  [MessageType.HOST_RECONCILE]: hostReconcilePayloadSchema,

  [MessageType.SESSION_OFFER]: sessionOfferPayloadSchema,
  [MessageType.SESSION_ACCEPT]: sessionAcceptPayloadSchema,
  [MessageType.SESSION_REJECT]: sessionRejectPayloadSchema,
  [MessageType.SESSION_OPEN]: sessionOpenPayloadSchema,
  [MessageType.SESSION_OPENED]: sessionOpenedPayloadSchema,
  [MessageType.SESSION_CLOSE]: sessionClosePayloadSchema,
  [MessageType.SESSION_CLOSED]: sessionClosedPayloadSchema,

  [MessageType.CHANNEL_OPEN]: channelOpenPayloadSchema,
  [MessageType.CHANNEL_OPENED]: channelOpenedPayloadSchema,

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
  [MessageType.EXECUTION_POLICY_UPDATE]: executionPolicyUpdatePayloadSchema,
  [MessageType.EXECUTION_APPROVAL_REQUESTED]: executionApprovalRequestedPayloadSchema,

  [MessageType.EXECUTION_CONTROL]: executionControlPayloadSchema,
  [MessageType.EXECUTION_CONTROL_STATE]: executionControlStatePayloadSchema,
  [MessageType.EXECUTION_INTERACTION]: executionInteractionPayloadSchema,
  [MessageType.EXECUTION_INTERACTION_DELTA]: executionInteractionDeltaPayloadSchema,
  [MessageType.EXECUTION_INTERACTION_COMPLETE]: executionInteractionCompletePayloadSchema,
  [MessageType.EXECUTION_INTERACTION_FAILED]: executionInteractionFailedPayloadSchema,
  [MessageType.EXECUTION_CONTROL_REQUEST]: executionControlRequestPayloadSchema,
  [MessageType.EXECUTION_CONTROL_REQUEST_RESOLVED]: executionControlRequestResolvedPayloadSchema,

  [MessageType.PROTOCOL_ACK]: protocolAckPayloadSchema,
  [MessageType.PROTOCOL_ERROR]: protocolErrorPayloadSchema,
};

export type ParsedUnifiedFrame =
  | HostOpenFrame
  | HostOpenedFrame
  | HostHeartbeatFrame
  | HostCapacityFrame
  | HostResumeFrame
  | HostReconcileFrame
  | SessionOfferFrame
  | SessionAcceptFrame
  | SessionRejectFrame
  | SessionOpenFrame
  | SessionOpenedFrame
  | SessionCloseFrame
  | SessionClosedFrame
  | ChannelOpenFrame
  | ChannelOpenedFrame
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
  | ExecutionPolicyUpdateFrame
  | ExecutionApprovalRequestedFrame
  | ExecutionControlFrame
  | ExecutionControlStateFrame
  | ExecutionInteractionFrame
  | ExecutionInteractionDeltaFrame
  | ExecutionInteractionCompleteFrame
  | ExecutionInteractionFailedFrame
  | ExecutionControlRequestFrame
  | ExecutionControlRequestResolvedFrame
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
