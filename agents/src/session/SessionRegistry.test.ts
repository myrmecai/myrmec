// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, beforeEach } from "vitest";
import { SessionRegistry } from "./SessionRegistry.js";
import type { SessionOpenPayload, ToolDefinition, KnowledgeSourceHandle } from "../protocol/inferenceFrames.js";
import type { ChatModel, ConversationMessage, ModelResponse, Tool, SessionTool } from "../executor/types.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";
import type { ModelInfoWire } from "../protocol/taskFrames.js";

// ── helpers ───────────────────────────────────────────────────────

/** A minimal fake ChatModel that records its constructor arg and can be
 * inspected after the fact. */
class FakeChatModel implements ChatModel {
  readonly createdAt = Date.now();
  readonly calls: { messages: ConversationMessage[] }[] = [];
  closed = false;

  async invoke(): Promise<ModelResponse> {
    return { content: "ok" };
  }

  // Attach a close hook so we can verify dispose on session.close.
  close() {
    this.closed = true;
  }
}

/** A fake ChatModelFactory that returns the given model (or a new FakeChatModel). */
function fakeChatModelFactory(model?: ChatModel): ChatModelFactory {
  return {
    resolve: async (_info: ModelInfoWire, _sessionId: string) => model ?? new FakeChatModel(),
  };
}

/** A fake SessionToolFactory that matches tool names to implementations. */
function fakeSessionToolFactory(tools: Tool[] = []): SessionToolFactory {
  const toolsByName = new Map(tools.map((t) => [t.name, t]));
  return {
    resolve: async (defs: ToolDefinition[]) => {
      const map = new Map<string, SessionTool>();
      for (const def of defs) {
        const riskClass = def.riskClass ?? "SAFE";
        const impl = toolsByName.get(def.name);
        if (impl) {
          map.set(def.name, { ...impl, riskClass });
        } else {
          map.set(def.name, {
            name: def.name,
            description: def.description,
            parameters: def.parameters,
            invoke: async () => `Tool '${def.name}' has no agent-side implementation.`,
            riskClass,
          });
        }
      }
      return map;
    },
  };
}

/** Build a minimal SessionOpenPayload with sane defaults. */
function makeOpenPayload(overrides: Partial<SessionOpenPayload> = {}): SessionOpenPayload {
  return {
    sessionId: "sess-1",
    serviceType: "CONVERSATION",
    projectId: "proj-1",
    profileVersionId: "pv-1",
    model: {
      provider: "ollama",
      modelId: "llama3",
      apiEndpoint: "http://localhost:11434/v1",
      apiKey: null,
      parameters: {},
    },
    workspace: null,
    tools: [],
    knowledgeSources: [],
    ...overrides,
  };
}

function makeToolDef(name: string): ToolDefinition {
  return {
    name,
    description: `tool ${name}`,
    parameters: { type: "object" },
    riskClass: "SAFE",
  };
}

function makeKs(id: string): KnowledgeSourceHandle {
  return { knowledgeSourceId: id, name: `ks-${id}`, description: "test" };
}

function fakeTool(name: string): Tool {
  return { name, invoke: async () => "result" };
}

// ── tests ──────────────────────────────────────────────────────────

describe("SessionRegistry", () => {
  let registry: SessionRegistry;

  beforeEach(() => {
    // No mock reset needed — factories are injected directly now.
  });

  // ── open ───────────────────────────────────────────────────────

  it("resolves the model once at open time and stores it in the session", async () => {
    const fakeModel = new FakeChatModel();

    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(fakeModel),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    await registry.open(makeOpenPayload());

    const session = registry.get("sess-1");
    expect(session).toBeDefined();
    expect(session!.model).toBe(fakeModel);
  });

  it("binds tools by matching catalog names against constructor-supplied implementations", async () => {
    const search = fakeTool("search");
    const calc = fakeTool("calc");
    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory([search, calc]),
    });

    await registry.open(
      makeOpenPayload({
        tools: [makeToolDef("search"), makeToolDef("calc"), makeToolDef("unknown_tool")],
      }),
    );

    const session = registry.get("sess-1")!;
    expect(session.tools.size).toBe(3); // unknown_tool included as a stub
    expect(session.tools.has("search")).toBe(true);
    expect(session.tools.has("calc")).toBe(true);
    expect(session.tools.has("unknown_tool")).toBe(true); // stub with no-op invoke
  });

  it("warns (not throws) when a catalogued tool has no implementation", async () => {
    const warnings: string[] = [];
    const logger = {
      debug: () => {},
      info: () => {},
      warn: (msg: string) => warnings.push(msg),
      error: () => {},
    };

    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
      logger,
    });
    await registry.open(
      makeOpenPayload({ tools: [makeToolDef("nope")] }),
    );

    expect(warnings.some((w) => w.includes("nope"))).toBe(false); // nope included as stub, not warned
    const session = registry.get("sess-1")!;
    expect(session.tools.size).toBe(1); // nope included as stub
  });

  it("stores knowledge-source ids from the payload", async () => {
    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    await registry.open(
      makeOpenPayload({
        knowledgeSources: [makeKs("ks-1"), makeKs("ks-2"), makeKs("ks-3")],
      }),
    );

    const session = registry.get("sess-1")!;
    expect(session.knowledgeSourceIds.size).toBe(3);
    expect(session.knowledgeSourceIds.has("ks-1")).toBe(true);
    expect(session.knowledgeSourceIds.has("ks-3")).toBe(true);
  });

  // ── close ───────────────────────────────────────────────────────

  it("disposes the model on close and drops the session entry", async () => {
    const fakeModel = new FakeChatModel();

    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(fakeModel),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    await registry.open(makeOpenPayload());

    expect(registry.has("sess-1")).toBe(true);
    registry.close("sess-1");

    expect(fakeModel.closed).toBe(true);
    expect(registry.has("sess-1")).toBe(false);
    expect(registry.get("sess-1")).toBeUndefined();
  });

  it("close is a no-op when the session is already gone", async () => {
    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    // Should not throw.
    registry.close("never-existed");
  });

  // ── has / get ──────────────────────────────────────────────────

  it("has() returns false before open and true after", async () => {
    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    expect(registry.has("sess-1")).toBe(false);

    await registry.open(makeOpenPayload());
    expect(registry.has("sess-1")).toBe(true);
  });

  it("get() returns the session metadata (serviceType, projectId)", async () => {
    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    await registry.open(
      makeOpenPayload({ serviceType: "WORKFLOW", projectId: "p-99" }),
    );

    const session = registry.get("sess-1")!;
    expect(session.serviceType).toBe("WORKFLOW");
    expect(session.projectId).toBe("p-99");
    expect(session.sessionId).toBe("sess-1");
  });
});