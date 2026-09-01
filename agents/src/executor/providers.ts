// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Execution provider factories for the agent runtime.
 *
 * Two factory interfaces that decouple *how* the agent resolves its ChatModel
 * and tool implementations from the session lifecycle:
 *
 * - {@link ChatModelFactory} — produces a {@link ChatModel} for a session.
 *   The real implementation ({@link LangChainChatModelFactory}) delegates to
 *   the existing `resolveChatModel` / LangChain adapter. The stub
 *   implementation ({@link StubChatModelFactory} in `stub.ts`) returns a
 *   {@link StubChatModel} with canned responses for deterministic E2E tests.
 *
 * - {@link SessionToolFactory} — produces a `Map<string, SessionTool>` for a
 *   session. The real implementation ({@link DefaultSessionToolFactory})
 *   matches engine tool definitions against agent-side implementations. The
 *   stub implementation ({@link StubSessionToolFactory} in `stub.ts`) returns
 *   stub tools with canned results.
 *
 * Both factories are constructed **inside the worker** from serializable config
 * (they cannot cross the `worker_threads` boundary as instances). The factory
 * functions {@link createChatModelFactory} and {@link createSessionToolFactory}
 * resolve the correct implementation from a {@link ProviderConfig}.
 */
import type { ChatModel, SessionTool, Tool, RiskClass } from "./types.js";
import type { ModelInfoWire } from "../protocol/taskFrames.js";
import type { ToolDefinition } from "../protocol/inferenceFrames.js";
import { resolveChatModel } from "../models/resolveModel.js";

// ── Interfaces ────────────────────────────────────────────────────────

/**
 * Factory that resolves a {@link ChatModel} for a session.
 *
 * Called once per `session.open`; the returned model is reused for all turns
 * in that session. Two implementations: real (LangChain) and stub (canned
 * responses for E2E tests).
 */
export interface ChatModelFactory {
  /**
   * @param modelInfo  the engine's model descriptor (provider, modelId, etc.)
   * @param sessionId  the session ID — lets stub implementations scope
   *                   per-session handler state
   */
  resolve(modelInfo: ModelInfoWire, sessionId: string): Promise<ChatModel>;
}

/**
 * Factory that resolves tool implementations for a session.
 *
 * Called once per `session.open`; the returned map is reused for all turns.
 * Two implementations: real (agent-side implementations) and stub (canned
 * results for E2E tests).
 */
export interface SessionToolFactory {
  resolve(toolDefs: ToolDefinition[]): Promise<Map<string, SessionTool>>;
}

// ── Config ────────────────────────────────────────────────────────────

/**
 * Serializable config for selecting execution providers.
 * Crosses the `worker_threads` boundary as plain data.
 */
export interface ProviderConfig {
  /** `'stub'` for deterministic E2E tests, `'real'` for production. */
  mode: "stub" | "real";
  /** Path to a handler module (only used when `mode='stub'`). */
  stubModulePath?: string;
  /** Agent-side tool implementations (only used when `mode='real'`). */
  realTools?: Tool[];
}

// ── Real implementations ──────────────────────────────────────────────

/**
 * Real ChatModel factory — delegates to the existing `resolveChatModel`
 * function which lazy-imports LangChain and constructs the provider model.
 */
export class LangChainChatModelFactory implements ChatModelFactory {
  async resolve(modelInfo: ModelInfoWire, _sessionId: string): Promise<ChatModel> {
    return resolveChatModel(modelInfo);
  }
}

/**
 * Real SessionTool factory — matches engine tool definitions against
 * agent-side implementations by name. Tools without an implementation get a
 * stub `invoke` that returns an error message (enables HITL approval flows
 * for tools that don't have agent-side implementations).
 */
export class DefaultSessionToolFactory implements SessionToolFactory {
  private readonly toolsByName: Map<string, Tool>;

  constructor(tools: Tool[] = []) {
    this.toolsByName = new Map(tools.map((t) => [t.name, t]));
  }

  async resolve(toolDefs: ToolDefinition[]): Promise<Map<string, SessionTool>> {
    const tools = new Map<string, SessionTool>();
    for (const def of toolDefs) {
      const riskClass: RiskClass = def.riskClass ?? "SAFE";
      const impl = this.toolsByName.get(def.name);
      if (impl) {
        tools.set(def.name, { ...impl, riskClass });
      } else {
        tools.set(def.name, {
          name: def.name,
          description: def.description,
          parameters: def.parameters,
          invoke: async () => `Tool '${def.name}' has no agent-side implementation.`,
          riskClass,
        });
      }
    }
    return tools;
  }
}

// ── Factory functions ─────────────────────────────────────────────────

/**
 * Create a {@link ChatModelFactory} from config.
 * Called inside the worker from serializable config.
 *
 * Async because the stub implementation is loaded via dynamic `import()`
 * (not `require()`) to work in ESM mode.
 */
export async function createChatModelFactory(config: ProviderConfig): Promise<ChatModelFactory> {
  if (config.mode === "stub") {
    const { StubChatModelFactory } = await import("./stub.js");
    return new StubChatModelFactory(config.stubModulePath);
  }
  return new LangChainChatModelFactory();
}

/**
 * Create a {@link SessionToolFactory} from config.
 * Called inside the worker from serializable config.
 */
export async function createSessionToolFactory(config: ProviderConfig): Promise<SessionToolFactory> {
  if (config.mode === "stub") {
    const { StubSessionToolFactory } = await import("./stub.js");
    return new StubSessionToolFactory(config.stubModulePath);
    return new StubSessionToolFactory(config.stubModulePath);
  }
  return new DefaultSessionToolFactory(config.realTools);
}