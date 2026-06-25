// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * WebSocket message types and close codes for the Myrmec Engine ↔ Agent
 * protocol. Ported verbatim from the Python SDK (`myrmec/agent/messages.py`)
 * — keep the string values byte-identical so a TypeScript agent and the
 * existing engine speak the same wire protocol.
 */

/** WebSocket message types for Engine ↔ Agent communication. */
export const MessageType = {
  // Engine → Agent
  TASK_ASSIGN: "task.assign",
  TASK_CANCEL: "task.cancel",
  PING: "ping",

  // Conversational sessions (Engine → Agent)
  CONVERSATION_TURN_ASSIGN: "conversation.turn.assign",
  CONVERSATION_TURN_CANCEL: "conversation.turn.cancel",

  // HITL approvals (Engine → Agent)
  APPROVAL_DECISION: "approval.decision",

  // Reserve-time binding (Engine → Agent)
  AGENT_BIND: "agent.bind",
  AGENT_RELEASE: "agent.release",

  // Bind handshake (Host → Engine)
  AGENT_BIND_ACK: "agent.bind.ack",
  AGENT_BIND_NACK: "agent.bind.nack",

  // Agent Host control (Host → Engine)
  HOST_ANNOUNCE: "host.announce",

  // Agent → Engine
  TASK_ACCEPT: "task.accept",
  TASK_REJECT: "task.reject",
  TASK_PROGRESS: "task.progress",
  TASK_COMPLETE: "task.complete",
  TASK_FAILED: "task.failed",
  LOG: "log",
  TOOL_CALL: "tool.call",
  TOOL_RESULT: "tool.result",
  TOKEN_USAGE: "token.usage",
  TASK_METRICS: "task.metrics",
  PONG: "pong",
  DISCONNECT: "disconnect",

  // Conversation socket attach (Agent → Engine)
  CONVERSATION_ATTACH: "conversation.attach",

  // Conversational sessions (Agent → Engine)
  MESSAGE_DELTA: "message.delta",
  MESSAGE_COMPLETE: "message.complete",
  TASK_CANCELLED: "task.cancelled",

  // HITL approvals (Agent → Engine)
  APPROVAL_REQUEST: "approval.request",
} as const;

export type MessageType = (typeof MessageType)[keyof typeof MessageType];

/**
 * WebSocket close codes for agent communication. The 4xxx codes drive the
 * Supervisor's reconnect/re-register reaction (REQ-A-002).
 */
export const CloseCode = {
  NORMAL: 1000, // Normal closure
  GOING_AWAY: 1001, // Server shutdown
  TOKEN_EXPIRED: 4001, // Token expired — refresh and reconnect
  INVALID_TOKEN: 4002, // Invalid token — re-register
  AGENT_DEACTIVATED: 4003, // Agent disabled by admin
  DUPLICATE_CONNECTION: 4004, // Another instance connected
} as const;

export type CloseCode = (typeof CloseCode)[keyof typeof CloseCode];
