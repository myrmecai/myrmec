// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Executor seams: the small interfaces the turn loop runs against.
 *
 * Ported from the *core* of the Python `LangChainExecutor` (bind tools →
 * invoke → tool_calls loop), but with the engine-owned concerns the TS
 * contracts moved server-side stripped out (message assembly, knowledge
 * compilation, provider resolution, tool registry, workspace cloning). What
 * remains is the provider-agnostic turn loop, defined here against two seams
 * so it is unit-testable with fakes and real LangChain JS adapters drop in as
 * a later slice (§9.3 seam discipline).
 */
import type { ToolCallRecord } from "../models/index.js";

/** A tool call the model requested for one turn. */
export interface ModelToolCall {
  /** Provider-assigned id; the matching tool result echoes it back. */
  id: string;
  name: string;
  /** Parsed arguments (already JSON-decoded by the model adapter). */
  args: Record<string, unknown>;
}

/** Token usage a provider may report for a single model call (REQ-A-071). */
export interface TokenUsage {
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
}

/** One part of a multimodal message: either a text run or an inline image
 * referenced by data URL (#103 Slice A). Mirrors the subset of LangChain's
 * complex message content the conversation path produces, kept local so the
 * executor types carry no provider import. */
export type MessageContentPart =
  | { type: "text"; text: string }
  | { type: "image_url"; image_url: { url: string } };

/** A message in the running turn transcript the model sees. Superset of the
 * assembled {@link TurnMessage} that also carries the tool-call linkage the
 * loop appends between iterations. `content` is plain text in the tool loop;
 * the conversation path may widen a user message to multimodal parts. */
export interface ConversationMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string | MessageContentPart[];
  /** assistant turns: the tool calls the model asked for. */
  toolCalls?: ModelToolCall[];
  /** tool turns: which call this message answers. */
  toolCallId?: string;
}

/** One model invocation's result: either tool calls or a final answer. */
export interface ModelResponse {
  /** Final assistant text. Present (and authoritative) when there are no
   * tool calls; may also accompany tool calls as the model's reasoning. */
  content?: string;
  /** Tool calls the model requested this turn; empty/absent ⇒ final answer. */
  toolCalls?: ModelToolCall[];
  /** Provider-reported usage for this call, if any. */
  usage?: TokenUsage;
}

/** One chunk of a streamed model response. The conversation path consumes a
 * sequence of these to emit `message.delta` frames live. `content` is the
 * incremental text for this chunk; `usage` (when present) typically arrives on
 * the final chunk. */
export interface ModelStreamChunk {
  content?: string;
  usage?: TokenUsage;
}

/** The static description of a tool handed to the model adapter. */
export interface ToolSpec {
  name: string;
  description?: string;
  /** JSON-schema-ish parameter description; opaque to the loop. */
  parameters?: Record<string, unknown>;
}

/** A callable tool: its spec plus an async invocation. */
export interface Tool extends ToolSpec {
  invoke(args: Record<string, unknown>): Promise<unknown>;
}

/** The model adapter seam. The real implementation (LangChain JS / provider
 * SDK) is a later slice; the loop only needs this one method. */
export interface ChatModel {
  /**
   * Invoke the model with the running transcript and the tools it may call.
   * @param messages the full conversation so far (system → … → latest)
   * @param tools    specs the model may call this turn (may be empty)
   */
  invoke(messages: ConversationMessage[], tools: ToolSpec[]): Promise<ModelResponse>;

  /**
   * Stream the model's reply as a sequence of {@link ModelStreamChunk}s. Used
   * by the conversation path to emit token deltas live. Optional: callers that
   * need streaming fall back to {@link invoke} (emitting the whole reply as one
   * chunk) when an adapter does not implement it.
   */
  stream?(
    messages: ConversationMessage[],
    tools: ToolSpec[],
  ): AsyncIterable<ModelStreamChunk>;
}

/** Cooperative cancellation, flipped by a `task.cancel` from the engine. */
export interface CancellationSignal {
  readonly cancelled: boolean;
}

/**
 * Optional lifecycle callbacks the turn loop fires so a caller can stream
 * observability frames (progress, tool.call/tool.result) live, the way the
 * Python `TaskContext` did via `report_progress` / `track_tool_call`. All are
 * optional — the loop stays a pure function when none are supplied (the unit
 * tests rely on that). Errors thrown by a callback propagate to the caller.
 */
export interface ExecutorEvents {
  /** Fired once per iteration with a clamped 0–90 progress value. */
  onProgress?(progress: number, iteration: number): void | Promise<void>;
  /** Fired just before a tool runs (record has args + startedAt, no result). */
  onToolStart?(record: ToolCallRecord): void | Promise<void>;
  /** Fired once a tool settles (record has result/error + completedAt). */
  onToolEnd?(record: ToolCallRecord): void | Promise<void>;
}
