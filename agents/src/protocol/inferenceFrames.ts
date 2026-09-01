// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Session and inference wire frames for the unified dispatch protocol (§5).
 * Ported from the engine DTOs — keep field names byte-identical.
 */

// ── session.open payload (§5.2) ──

export interface ModelConfig {
  provider: string;
  modelId: string;
  apiEndpoint: string | null;
  apiKey: string | null;
  parameters: Record<string, unknown>;
}

export interface WorkspaceConfig {
  repoUrl: string;
  branch: string;
  subPath: string | null;
  repoToken: string | null;
}

export interface ToolDefinition {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
  riskClass: "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE";
}

export interface KnowledgeSourceHandle {
  knowledgeSourceId: string;
  name: string;
  description: string;
}

export interface SessionOpenPayload {
  sessionId: string;
  serviceType: "WORKFLOW" | "CONVERSATION";
  projectId: string;
  profileVersionId: string;
  model: ModelConfig;
  workspace: WorkspaceConfig | null;
  tools: ToolDefinition[];
  knowledgeSources: KnowledgeSourceHandle[];
  /** When true, DESTRUCTIVE/IRREVERSIBLE tools require human approval before
   *  execution. The engine reads this from projects.auto_hitl_on_destructive. */
  autoHitlOnDestructive?: boolean;
}

// ── session.close payload ──

export interface SessionClosePayload {
  sessionId: string;
}

// ── inference.assign payload (§5.3) ──

export interface ContentPart {
  type: "text" | "image";
  text?: string;
  attachmentId?: string;
  mediaType?: string;
  readContentPath?: string;
}

export interface ToolCall {
  id: string;
  name: string;
  args: Record<string, unknown>;
}

export interface InferenceMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string | null;
  parts?: ContentPart[];
  toolCalls?: ToolCall[];
}

export interface GenerationConfig {
  temperature?: number;
  maxOutputTokens?: number;
}

export interface ResponseRouting {
  sequenceNo: number;
}

export interface InferenceAssignPayload {
  requestId: string;
  sessionId: string;
  serviceType: "WORKFLOW" | "CONVERSATION";
  messages: InferenceMessage[];
  activeToolNames: string[];
  generation: GenerationConfig | null;
  timeoutSeconds: number | null;
  stream: boolean;
  response: ResponseRouting;
}

// ── inference result frames (§5.5) ──

export interface InferenceAcceptPayload {
  requestId: string;
  sessionId: string;
}

export interface InferenceDeltaPayload {
  requestId: string;
  sessionId: string;
  sequenceNo: number;
  deltaIndex: number;
  content: string;
}

export interface InferenceToolCallPayload {
  requestId: string;
  sessionId: string;
  toolCallId: string;
  name: string;
  args: Record<string, unknown>;
}

export interface InferenceToolResultPayload {
  requestId: string;
  sessionId: string;
  toolCallId: string;
  result: string;
  isError: boolean;
}

export interface InferenceCompletePayload {
  requestId: string;
  sessionId: string;
  sequenceNo: number;
  content: string;
  tokenCount?: number;
  modelCode?: string;
}

export interface InferenceFailedPayload {
  requestId: string;
  sessionId: string;
  errorCode: string;
  message: string;
  retryHint?: string;
}

export interface InferenceCancelPayload {
  requestId: string;
  sessionId: string;
}

export interface InferenceCancelledPayload {
  requestId: string;
  sessionId: string;
  sequenceNo: number;
  partialContent?: string;
}