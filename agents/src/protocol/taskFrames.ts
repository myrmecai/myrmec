// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Task-lifecycle wire frames for the Engine ↔ Agent protocol.
 *
 * Ported verbatim from the Python SDK payload models (`models.py`): the
 * inbound `task.assign` / `task.cancel` shapes (validated at the boundary with
 * zod, REQ-A-074) and the outbound builders the agent emits during execution
 * (`task.accept` / `task.reject` / `task.progress` / `task.complete` /
 * `task.failed` / `tool.call` / `tool.result`). Field names are the engine's
 * camelCase aliases, kept byte-identical so a TS agent and the existing engine
 * speak the same protocol.
 */
import { z } from "zod";
import { MessageType } from "./messages.js";
import { makeEnvelope, type Envelope } from "./envelope.js";

// ==================== Inbound payload schemas ====================

/** LLM model info attached to a task (Python `ModelInfo`). */
export const modelInfoSchema = z.object({
  provider: z.string(),
  modelId: z.string(),
  apiEndpoint: z.string().nullish(),
  apiKey: z.string().nullish(),
  parameters: z.record(z.string(), z.unknown()).default({}),
});
export type ModelInfoWire = z.infer<typeof modelInfoSchema>;

/** A tool the engine declares available for a task (Python `ToolDefinition`). */
export const toolDefinitionSchema = z.object({
  name: z.string(),
  description: z.string().default(""),
  parameters: z.record(z.string(), z.unknown()).default({}),
});
export type ToolDefinitionWire = z.infer<typeof toolDefinitionSchema>;

/** A single resolved knowledge document (Python `KnowledgeEntry`). */
export const knowledgeEntrySchema = z.object({
  category: z.string(),
  name: z.string(),
  content: z.string(),
  priority: z.number().default(0),
});
export type KnowledgeEntryWire = z.infer<typeof knowledgeEntrySchema>;

/** Workspace clone config (Python `WorkspaceConfig`). */
export const workspaceConfigSchema = z.object({
  repoUrl: z.string(),
  branch: z.string().default("main"),
  subPath: z.string().nullish(),
  repoToken: z.string().nullish(),
});
export type WorkspaceConfigWire = z.infer<typeof workspaceConfigSchema>;

/** Knowledge + workspace context embedded in a task (Python `KnowledgeContext`). */
export const knowledgeContextSchema = z.object({
  knowledge: z.array(knowledgeEntrySchema).default([]),
  knowledgeCharCount: z.number().default(0),
  workspace: workspaceConfigSchema.nullish(),
  // `rag` is opaque to the agent in this slice.
  rag: z.unknown().nullish(),
});
export type KnowledgeContextWire = z.infer<typeof knowledgeContextSchema>;

/** `task.assign` payload (Python `TaskAssignPayload`). */
export const taskAssignPayloadSchema = z.object({
  taskId: z.string(),
  workflowId: z.string(),
  stepIndex: z.number(),
  stepName: z.string(),
  systemPrompt: z.string().nullish(),
  stepPrompt: z.string().nullish(),
  input: z.record(z.string(), z.unknown()).default({}),
  tools: z.array(toolDefinitionSchema).default([]),
  timeoutSeconds: z.number().default(300),
  model: modelInfoSchema.nullish(),
  context: knowledgeContextSchema.nullish(),
});
export type TaskAssignPayload = z.infer<typeof taskAssignPayloadSchema>;

/** `task.cancel` payload (Python `TaskCancelPayload`). */
export const taskCancelPayloadSchema = z.object({
  taskId: z.string(),
  reason: z.string().default("Cancelled by Engine"),
});
export type TaskCancelPayload = z.infer<typeof taskCancelPayloadSchema>;

// ==================== Outbound frame builders ====================

/** `task.accept` — the agent took the task. */
export function taskAccept(taskId: string): Envelope {
  return makeEnvelope(MessageType.TASK_ACCEPT, { taskId });
}

/** `task.reject` — the agent declined (e.g. busy). */
export function taskReject(taskId: string, reason: string): Envelope {
  return makeEnvelope(MessageType.TASK_REJECT, { taskId, reason });
}

/** `task.progress` — coarse 0–100 progress. */
export function taskProgress(
  taskId: string,
  progress: number,
  message?: string,
): Envelope {
  const clamped = Math.max(0, Math.min(100, Math.trunc(progress)));
  return makeEnvelope(MessageType.TASK_PROGRESS, {
    taskId,
    progress: clamped,
    ...(message !== undefined ? { message } : {}),
  });
}

/** `task.complete` — terminal success; `result` is an opaque output object. */
export function taskComplete(
  taskId: string,
  result: Record<string, unknown>,
): Envelope {
  return makeEnvelope(MessageType.TASK_COMPLETE, { taskId, result });
}

/** Optional detail for a failure frame. */
export interface TaskFailedDetail {
  error: string;
  errorCode?: string;
  retryAfterSeconds?: number;
}

/** `task.failed` — terminal failure with a classified code. */
export function taskFailed(taskId: string, detail: TaskFailedDetail): Envelope {
  return makeEnvelope(MessageType.TASK_FAILED, {
    taskId,
    error: detail.error,
    ...(detail.errorCode !== undefined ? { errorCode: detail.errorCode } : {}),
    ...(detail.retryAfterSeconds !== undefined
      ? { retryAfterSeconds: detail.retryAfterSeconds }
      : {}),
  });
}

/** `tool.call` — emitted when a tool invocation starts (audit). */
export function toolCall(
  taskId: string,
  detail: { toolName: string; callId: string; input: Record<string, unknown> },
): Envelope {
  return makeEnvelope(MessageType.TOOL_CALL, {
    taskId,
    toolName: detail.toolName,
    callId: detail.callId,
    input: detail.input,
  });
}

/** `tool.result` — emitted when a tool invocation settles (audit). */
export function toolResult(
  taskId: string,
  detail: {
    callId: string;
    durationMs: number;
    output?: Record<string, unknown>;
    error?: string;
  },
): Envelope {
  return makeEnvelope(MessageType.TOOL_RESULT, {
    taskId,
    callId: detail.callId,
    durationMs: detail.durationMs,
    output: detail.output ?? null,
    ...(detail.error !== undefined ? { error: detail.error } : {}),
  });
}
