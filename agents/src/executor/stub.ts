// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Stub execution providers for deterministic E2E tests.
 *
 * These implementations of {@link ChatModelFactory} and
 * {@link SessionToolFactory} return canned LLM responses and tool results,
 * letting E2E tests exercise the **real agent runtime** (session management,
 * context assembly, WebSocket protocol, HITL approval flow, streaming)
 * without Ollama or real tool side effects.
 *
 * Per-test behavior is controlled via a **handler module** — a TypeScript file
 * whose default export satisfies {@link StubHandlers}. The module path is
 * passed to the worker via `MYRMEC_STUB_MODULE` and dynamically imported
 * inside the worker thread.
 *
 * Handler state is **per-worker** (loaded once), not per-session. The
 * `sessionId` field in {@link LlmStubContext} lets handlers scope their own
 * state per-session when a worker runs multiple sessions concurrently.
 */
import type {
  ChatModel,
  ConversationMessage,
  ModelResponse,
  ModelStreamChunk,
  ModelToolCall,
  SessionTool,
  ToolSpec,
  TokenUsage,
  RiskClass,
} from "./types.js";
import type { ChatModelFactory, SessionToolFactory } from "./providers.js";
import type { ModelInfoWire } from "../protocol/taskFrames.js";
import type { ToolDefinition } from "../protocol/inferenceFrames.js";
import { pathToFileURL } from "node:url";

// ── Handler contract ──────────────────────────────────────────────────

/**
 * Handlers a test provides to control stub behavior.
 * All fields are optional — unset handlers fall back to defaults.
 *
 * A handler module is a standard ES module with a `default export` satisfying
 * this interface:
 *
 * ```typescript
 * import type { StubHandlers } from '@myrmec/agents'
 *
 * export default {
 *   llm: ({ messages, tools, iteration, sessionId }) => { ... },
 *   tools: { delete_file: async (args) => 'deleted' },
 * } satisfies StubHandlers
 * ```
 */
export interface StubHandlers {
  /**
   * Called for each LLM invocation within a session.
   * Return a {@link StubLlmResponse} to control the model's reply, or
   * `undefined` to use the default (`{ content: "stub-response" }`).
   */
  llm?: (ctx: LlmStubContext) => StubLlmResponse | undefined;

  /**
   * Tool results keyed by tool name. Each handler is called when the
   * corresponding tool is invoked. Unregistered tools return a default.
   */
  tools?: Record<string, (args: Record<string, unknown>) => unknown | Promise<unknown>>;
}

/**
 * Context passed to the LLM stub handler for each invocation.
 */
export interface LlmStubContext {
  /** The full conversation transcript so far. */
  messages: ConversationMessage[];
  /** Tool specs available this turn (may be empty). */
  tools: ToolSpec[];
  /** 1-based iteration within the current tool loop. */
  iteration: number;
  /** Session ID — lets handlers scope state per-session. */
  sessionId: string;
  /** The resolved model the stub is standing in for. Orchestration
   * handlers branch on `modelInfo.modelId`, not invocation order. */
  modelInfo?: ModelInfoWire;
}

/**
 * Response from the LLM stub handler.
 */
export interface StubLlmResponse {
  /** Final assistant text. */
  content?: string;
  /** Tool calls the model requests this turn. */
  toolCalls?: ModelToolCall[];
  /**
   * Split content into N streamed chunks (for streaming tests).
   * When provided, the stub's `stream()` yields each chunk as a delta.
   * When absent, `stream()` yields the full content as one chunk.
   */
  streamChunks?: string[];
  /** Simulated token usage. */
  usage?: TokenUsage;
  /**
   * Simulate a provider error (e.g. 429 rate-limit, 500 server error).
   * When set, the stub throws instead of returning a response. The
   * executor catches this and emits an `inference.failed` frame with
   * the given code. Lets handlers test retry/budget/error paths.
   */
  error?: { code: string; message: string; retryAfter?: string };
}

// ── Stub ChatModel ────────────────────────────────────────────────────

/**
 * Stub `ChatModel` that returns canned responses from a {@link StubHandlers}
 * instance. Implements both `invoke()` and `stream()` so the full
 * conversation/workflow paths are exercised.
 */
class StubChatModel implements ChatModel {
  private iteration = 0;

  constructor(
    private readonly handlers: StubHandlers | undefined,
    private readonly sessionId: string,
    private readonly modelInfo?: ModelInfoWire,
  ) {}

  async invoke(
    messages: ConversationMessage[],
    tools: ToolSpec[],
  ): Promise<ModelResponse> {
    this.iteration++;
    const ctx: LlmStubContext = {
      messages,
      tools,
      iteration: this.iteration,
      sessionId: this.sessionId,
      ...(this.modelInfo ? { modelInfo: this.modelInfo } : {}),
    };
    const result = this.handlers?.llm?.(ctx) ?? { content: "stub-response" };

    if (result.error) {
      throw createStubError(result.error);
    }

    return {
      content: result.content,
      toolCalls: result.toolCalls,
      usage: result.usage,
    };
  }

  async *stream(
    messages: ConversationMessage[],
    tools: ToolSpec[],
  ): AsyncIterable<ModelStreamChunk> {
    this.iteration++;
    const ctx: LlmStubContext = {
      messages,
      tools,
      iteration: this.iteration,
      sessionId: this.sessionId,
      ...(this.modelInfo ? { modelInfo: this.modelInfo } : {}),
    };
    const result = this.handlers?.llm?.(ctx) ?? { content: "stub-response" };

    if (result.error) {
      throw createStubError(result.error);
    }

    if (result.streamChunks && result.streamChunks.length > 0) {
      for (const chunk of result.streamChunks) {
        yield { content: chunk };
      }
    } else {
      yield { content: result.content ?? "stub-response" };
    }
    if (result.usage) yield { usage: result.usage };
    if (result.toolCalls) yield { toolCalls: result.toolCalls };
  }
}

// ── Stub ChatModelFactory ─────────────────────────────────────────────

/**
 * Stub {@link ChatModelFactory} that produces {@link StubChatModel} instances.
 *
 * The handler module is loaded via dynamic `import()` **inside `resolve()`**
 * (not fire-and-forget in the constructor) to prevent a race where
 * `session.open` fires before the module finishes loading. The import promise
 * is cached so the module loads once per worker.
 */
export class StubChatModelFactory implements ChatModelFactory {
  private handlersPromise: Promise<StubHandlers | undefined> | undefined;

  constructor(private readonly stubModulePath?: string) {}

  private async loadHandlers(): Promise<StubHandlers | undefined> {
    if (!this.stubModulePath) return undefined;
    if (!this.handlersPromise) {
      // Convert Windows paths to file:// URLs — Node.js ESM import() requires
      // file:// URLs, not bare Windows paths (e.g. C:\\... → file:///C:/...).
      const moduleUrl = pathToFileURL(this.stubModulePath).href;
      this.handlersPromise = import(moduleUrl).then(
        (mod) => mod.default as StubHandlers | undefined,
      );
    }
    return this.handlersPromise;
  }

  async resolve(modelInfo: ModelInfoWire, sessionId: string): Promise<ChatModel> {
    const handlers = await this.loadHandlers();
    return new StubChatModel(handlers, sessionId, modelInfo);
  }
}

// ── Stub SessionToolFactory ───────────────────────────────────────────

/**
 * Stub {@link SessionToolFactory} that produces tools with canned `invoke()`
 * results. Handler module loading follows the same pattern as
 * {@link StubChatModelFactory}.
 */
export class StubSessionToolFactory implements SessionToolFactory {
  private handlersPromise: Promise<StubHandlers | undefined> | undefined;

  constructor(private readonly stubModulePath?: string) {}

  private async loadHandlers(): Promise<StubHandlers | undefined> {
    if (!this.stubModulePath) return undefined;
    if (!this.handlersPromise) {
      const moduleUrl = pathToFileURL(this.stubModulePath).href;
      this.handlersPromise = import(moduleUrl).then(
        (mod) => mod.default as StubHandlers | undefined,
      );
    }
    return this.handlersPromise;
  }

  async resolve(
    toolDefs: ToolDefinition[],
  ): Promise<Map<string, SessionTool>> {
    const handlers = await this.loadHandlers();
    const tools = new Map<string, SessionTool>();
    for (const def of toolDefs) {
      const riskClass: RiskClass = def.riskClass ?? "SAFE";
      const handler = handlers?.tools?.[def.name];
      tools.set(def.name, {
        name: def.name,
        description: def.description,
        parameters: def.parameters,
        riskClass,
        invoke: async (args: Record<string, unknown>) => {
          if (handler) return handler(args);
          return "stub-tool-result";
        },
      });
    }
    return tools;
  }
}

// ── Helpers ───────────────────────────────────────────────────────────

/**
 * Create an Error with `code` and `retryAfter` properties attached, so the
 * executor's catch path can extract them for the `inference.failed` frame.
 */
function createStubError(error: {
  code: string;
  message: string;
  retryAfter?: string;
}): Error {
  const e = new Error(error.message);
  (e as unknown as Record<string, unknown>).code = error.code;
  if (error.retryAfter !== undefined) {
    (e as unknown as Record<string, unknown>).retryAfter = error.retryAfter;
  }
  return e;
}