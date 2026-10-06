// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect } from "vitest";
import { TurnExecutor } from "./TurnExecutor.js";
import type {
  ChatModel,
  ConversationMessage,
  ModelResponse,
  ModelStreamChunk,
  ModelToolCall,
  Tool,
  ToolSpec,
} from "./types.js";
import type { Task } from "../models/index.js";

/** Builds a minimal Task with the given context bits. */
function makeTask(overrides: Partial<Task["context"]> = {}): Task {
  return {
    taskId: "task-1",
    model: "openai/gpt-4o",
    context: {
      systemPrompt: overrides.systemPrompt ?? "You are helpful.",
      messages: overrides.messages ?? [{ role: "user", content: "Hi" }],
      toolNames: overrides.toolNames ?? [],
      workspace: overrides.workspace,
      metadata: overrides.metadata,
    },
  };
}

/** A scripted ChatModel that returns queued responses in order, recording the
 * messages/tools it was invoked with. */
class ScriptedModel implements ChatModel {
  readonly calls: { messages: ConversationMessage[]; tools: ToolSpec[] }[] = [];
  private readonly script: ModelResponse[];

  constructor(script: ModelResponse[]) {
    this.script = [...script];
  }

  async invoke(
    messages: ConversationMessage[],
    tools: ToolSpec[],
  ): Promise<ModelResponse> {
    // Snapshot the messages so later mutation doesn't change history.
    this.calls.push({ messages: structuredClone(messages), tools });
    const next = this.script.shift();
    if (!next) {
      throw new Error("ScriptedModel ran out of scripted responses");
    }
    return next;
  }
}

/** A model that always throws (provider error). */
class ThrowingModel implements ChatModel {
  constructor(private readonly message = "boom") {}
  async invoke(): Promise<ModelResponse> {
    throw new Error(this.message);
  }
}

/** A model that always asks for the same tool (drives the iteration cap). */
class LoopingModel implements ChatModel {
  constructor(private readonly call: ModelToolCall) {}
  async invoke(): Promise<ModelResponse> {
    return { toolCalls: [{ ...this.call }] };
  }
}

function fakeTool(
  name: string,
  impl: (args: Record<string, unknown>) => Promise<unknown> | unknown,
): Tool {
  return { name, invoke: async (args) => impl(args) };
}

const toolCall = (
  id: string,
  name: string,
  args: Record<string, unknown> = {},
): ModelToolCall => ({ id, name, args });

describe("TurnExecutor — terminal outcomes", () => {
  it("returns COMPLETE with the final content when no tools are called", async () => {
    const model = new ScriptedModel([{ content: "the answer is 42" }]);
    const result = await new TurnExecutor().execute(makeTask(), { model });

    expect(result).toEqual({
      taskId: "task-1",
      status: "COMPLETE",
      completion: "the answer is 42",
      toolCalls: [],
    });
  });

  it("treats an absent content as an empty completion", async () => {
    const model = new ScriptedModel([{}]);
    const result = await new TurnExecutor().execute(makeTask(), { model });

    expect(result.status).toBe("COMPLETE");
    expect(result.completion).toBe("");
  });

  it("treats an empty toolCalls array as a final answer", async () => {
    const model = new ScriptedModel([{ content: "done", toolCalls: [] }]);
    const result = await new TurnExecutor().execute(makeTask(), { model });

    expect(result.status).toBe("COMPLETE");
    expect(result.completion).toBe("done");
  });
});

describe("TurnExecutor — tool loop", () => {
  it("executes a tool call then returns the model's final answer", async () => {
    const model = new ScriptedModel([
      { content: "let me check", toolCalls: [toolCall("c1", "add", { a: 2, b: 3 })] },
      { content: "the sum is 5" },
    ]);
    const add = fakeTool("add", (args) => (args.a as number) + (args.b as number));

    const result = await new TurnExecutor().execute(makeTask(), {
      model,
      tools: [add],
    });

    expect(result.status).toBe("COMPLETE");
    expect(result.completion).toBe("the sum is 5");
    expect(result.toolCalls).toHaveLength(1);
    const rec = result.toolCalls[0];
    expect(rec.toolCallId).toBe("c1");
    expect(rec.toolName).toBe("add");
    expect(rec.args).toEqual({ a: 2, b: 3 });
    expect(rec.result).toBe(5);
    expect(rec.error).toBeUndefined();
    expect(typeof rec.startedAt).toBe("number");
    expect(rec.completedAt).toBeGreaterThanOrEqual(rec.startedAt);
  });

  it("feeds tool results back into the next model call as tool messages", async () => {
    const model = new ScriptedModel([
      { toolCalls: [toolCall("c1", "echo", { v: "hi" })] },
      { content: "final" },
    ]);
    const echo = fakeTool("echo", (args) => args.v);

    await new TurnExecutor().execute(makeTask(), { model, tools: [echo] });

    // Second invocation should see: system, user, assistant(toolCalls), tool.
    const second = model.calls[1].messages;
    expect(second.map((m) => m.role)).toEqual([
      "system",
      "user",
      "assistant",
      "tool",
    ]);
    const assistant = second[2];
    expect(assistant.toolCalls?.[0].id).toBe("c1");
    const toolMsg = second[3];
    expect(toolMsg.toolCallId).toBe("c1");
    expect(toolMsg.content).toBe("hi");
  });

  it("passes only tool specs (not the invoke fn) to the model", async () => {
    const model = new ScriptedModel([{ content: "done" }]);
    const tool: Tool = {
      name: "search",
      description: "search the web",
      parameters: { type: "object" },
      invoke: async () => "x",
    };

    await new TurnExecutor().execute(makeTask(), { model, tools: [tool] });

    expect(model.calls[0].tools).toEqual([
      { name: "search", description: "search the web", parameters: { type: "object" } },
    ]);
  });

  it("executes multiple tool calls in one round, preserving order", async () => {
    const model = new ScriptedModel([
      {
        toolCalls: [
          toolCall("c1", "a", {}),
          toolCall("c2", "b", {}),
        ],
      },
      { content: "done" },
    ]);
    const order: string[] = [];
    const a = fakeTool("a", () => {
      order.push("a");
      return "ra";
    });
    const b = fakeTool("b", () => {
      order.push("b");
      return "rb";
    });

    const result = await new TurnExecutor().execute(makeTask(), {
      model,
      tools: [a, b],
    });

    expect(order).toEqual(["a", "b"]);
    expect(result.toolCalls.map((c) => c.toolCallId)).toEqual(["c1", "c2"]);
  });

  it("JSON-stringifies non-string tool results in the transcript", async () => {
    const model = new ScriptedModel([
      { toolCalls: [toolCall("c1", "obj", {})] },
      { content: "done" },
    ]);
    const obj = fakeTool("obj", () => ({ ok: true, n: 1 }));

    await new TurnExecutor().execute(makeTask(), { model, tools: [obj] });

    const toolMsg = model.calls[1].messages[3];
    expect(toolMsg.content).toBe('{"ok":true,"n":1}');
  });
});

describe("TurnExecutor — tool failures are recorded, not fatal", () => {
  it("records an unknown tool as an error and continues", async () => {
    const model = new ScriptedModel([
      { toolCalls: [toolCall("c1", "missing", {})] },
      { content: "recovered" },
    ]);

    const result = await new TurnExecutor().execute(makeTask(), {
      model,
      tools: [],
    });

    expect(result.status).toBe("COMPLETE");
    expect(result.completion).toBe("recovered");
    expect(result.toolCalls[0].error).toBe("Unknown tool: missing");
    expect(result.toolCalls[0].result).toBeUndefined();
    // The error is fed back so the model can recover.
    expect(model.calls[1].messages[3].content).toBe("Unknown tool: missing");
  });

  it("records a thrown tool error and continues", async () => {
    const model = new ScriptedModel([
      { toolCalls: [toolCall("c1", "boom", {})] },
      { content: "recovered" },
    ]);
    const boom = fakeTool("boom", () => {
      throw new Error("kaboom");
    });

    const result = await new TurnExecutor().execute(makeTask(), {
      model,
      tools: [boom],
    });

    expect(result.status).toBe("COMPLETE");
    expect(result.toolCalls[0].error).toBe("kaboom");
    expect(result.toolCalls[0].completedAt).toBeGreaterThanOrEqual(
      result.toolCalls[0].startedAt,
    );
  });
});

describe("TurnExecutor — model failure and iteration cap", () => {
  it("returns FAILED/TRANSIENT/PROVIDER_ERROR when the model throws", async () => {
    const result = await new TurnExecutor().execute(makeTask(), {
      model: new ThrowingModel("upstream 503"),
    });

    expect(result.status).toBe("FAILED");
    expect(result.failure).toEqual({
      kind: "TRANSIENT",
      finishReason: "PROVIDER_ERROR",
      message: "upstream 503",
    });
    expect(result.toolCalls).toEqual([]);
  });

  it("returns FAILED/PERMANENT/MAX_ITERATIONS when the loop never converges", async () => {
    const model = new LoopingModel(toolCall("c", "noop", {}));
    const noop = fakeTool("noop", () => "again");

    const result = await new TurnExecutor({ maxIterations: 3 }).execute(
      makeTask(),
      { model, tools: [noop] },
    );

    expect(result.status).toBe("FAILED");
    expect(result.failure?.kind).toBe("PERMANENT");
    expect(result.failure?.finishReason).toBe("MAX_ITERATIONS");
    // 3 iterations, one tool call each.
    expect(result.toolCalls).toHaveLength(3);
  });
});

describe("TurnExecutor — cancellation", () => {
  it("returns CANCELLED before the first model call if already cancelled", async () => {
    const model = new ScriptedModel([{ content: "should not run" }]);
    const result = await new TurnExecutor().execute(makeTask(), {
      model,
      cancellation: { cancelled: true },
    });

    expect(result.status).toBe("CANCELLED");
    expect(result.completion).toBeUndefined();
    expect(model.calls).toHaveLength(0);
  });

  it("stops dispatching further tools once cancelled mid-round", async () => {
    let cancelled = false;
    const signal = {
      get cancelled() {
        return cancelled;
      },
    };
    const model = new ScriptedModel([
      {
        toolCalls: [toolCall("c1", "first", {}), toolCall("c2", "second", {})],
      },
    ]);
    const first = fakeTool("first", () => {
      cancelled = true; // cancel after the first tool runs
      return "r1";
    });
    const second = fakeTool("second", () => "r2");

    const result = await new TurnExecutor().execute(makeTask(), {
      model,
      tools: [first, second],
      cancellation: signal,
    });

    expect(result.status).toBe("CANCELLED");
    // Only the first tool executed; the second was skipped.
    expect(result.toolCalls.map((c) => c.toolName)).toEqual(["first"]);
  });
});

describe("TurnExecutor — message assembly", () => {
  it("prepends the system prompt and preserves history order", async () => {
    const model = new ScriptedModel([{ content: "ok" }]);
    const task = makeTask({
      systemPrompt: "SYS",
      messages: [
        { role: "user", content: "u1" },
        { role: "assistant", content: "a1" },
        { role: "user", content: "u2" },
      ],
    });

    await new TurnExecutor().execute(task, { model });

    expect(model.calls[0].messages).toEqual([
      { role: "system", content: "SYS" },
      { role: "user", content: "u1" },
      { role: "assistant", content: "a1" },
      { role: "user", content: "u2" },
    ]);
  });

  it("omits the system message when there is no system prompt", async () => {
    const model = new ScriptedModel([{ content: "ok" }]);
    const task = makeTask({ systemPrompt: "", messages: [{ role: "user", content: "hi" }] });

    await new TurnExecutor().execute(task, { model });

    expect(model.calls[0].messages.map((m) => m.role)).toEqual(["user"]);
  });
});

// -- Task 7: streaming onOutput + the third ChatModel argument ---------

describe("TurnExecutor — onOutput streaming callback (Task 7)", () => {
  it("streams sanitized fragments through onOutput and assembles the final response ONCE", async () => {
    class FragmentingStreamModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        throw new Error("streaming path expected");
      }
      async *stream(
        _messages: ConversationMessage[],
        _tools: ToolSpec[],
        _options?: { signal?: AbortSignal },
      ): AsyncIterable<ModelStreamChunk> {
        yield { content: "Hel" };
        yield { content: "lo " };
        yield { content: "world" };
        yield {
          usage: { promptTokens: 3, completionTokens: 4, totalTokens: 7 },
        };
      }
    }

    const outputs: string[] = [];
    const result = await new TurnExecutor().execute(makeTask(), {
      model: new FragmentingStreamModel(),
      onOutput: async (text) => {
        outputs.push(text);
      },
    });

    expect(result.status).toBe("COMPLETE");
    // Each fragment reached the output callback (sanitization is the
    // CALLER's pipeline; the executor hands the provisional fragments).
    expect(outputs).toEqual(["Hel", "lo ", "world"]);
    // The final response is assembled ONCE and the usage recorded.
    expect(result.completion).toBe("Hello world");
    expect(result.usage).toEqual({ promptTokens: 3, completionTokens: 4, totalTokens: 7 });
  });

  it("fragmented streamed tool calls are assembled once (never executed as fragments)", async () => {
    let streamCalls = 0;
    class FragmentedToolStreamModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        throw new Error("streaming path expected");
      }
      async *stream(
        _messages: ConversationMessage[],
        _tools: ToolSpec[],
      ): AsyncIterable<ModelStreamChunk> {
        streamCalls += 1;
        if (streamCalls === 1) {
          yield { content: "let me check" };
          // The provider streams ONE tool call split across chunks.
          yield { toolCalls: [{ id: "c1", name: "add", args: { a: 2, b: 3 } }] };
          yield { usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } };
          return;
        }
        yield {
          content: "the sum is 5",
          usage: { promptTokens: 2, completionTokens: 2, totalTokens: 4 },
        };
      }
    }
    const add = fakeTool("add", (args) => (args.a as number) + (args.b as number));

    const outputs: string[] = [];
    const result = await new TurnExecutor().execute(makeTask(), {
      model: new FragmentedToolStreamModel(),
      tools: [add],
      onOutput: async (text) => {
        outputs.push(text);
      },
    });

    // The assembled tool call executed exactly once with complete args.
    expect(result.status).toBe("COMPLETE");
    expect(result.toolCalls).toHaveLength(1);
    expect(result.toolCalls[0].toolName).toBe("add");
    expect(result.toolCalls[0].result).toBe(5);
    // Tool-call fragments never leak through the output callback: only
    // CONTENT fragments streamed (both turns' text).
    expect(outputs).toEqual(["let me check", "the sum is 5"]);
  });

  it("an empty stream falls back to invoke (the safe fallback path)", async () => {
    class EmptyStreamModel implements ChatModel {
      public invokeCalls = 0;
      async invoke(): Promise<ModelResponse> {
        this.invokeCalls += 1;
        return { content: "invoke fallback", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } };
      }
      // A stream that yields NOTHING and ends (degenerate provider).
      // eslint-disable-next-line require-yield
      async *stream(): AsyncIterable<ModelStreamChunk> {
        return;
      }
    }
    const model = new EmptyStreamModel();
    const outputs: string[] = [];
    const result = await new TurnExecutor().execute(makeTask(), {
      model,
      onOutput: async (text) => {
        outputs.push(text);
      },
    });
    expect(result.status).toBe("COMPLETE");
    expect(result.completion).toBe("invoke fallback");
    expect(outputs).toEqual(["invoke fallback"]);
  });

  it("a stream that THROWS with NOTHING yielded falls back to invoke; a partial stream failure is a provider error", async () => {
    class EarlyThrowStreamModel implements ChatModel {
      public invokeCalls = 0;
      async invoke(): Promise<ModelResponse> {
        this.invokeCalls += 1;
        return {
          content: "recovered via invoke",
          usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
        };
      }
      /* eslint-disable require-yield -- a generator that only throws */
      async *stream(): AsyncIterable<ModelStreamChunk> {
        throw new Error("stream died immediately");
      }
      /* eslint-enable require-yield */
    }
    const outputs: string[] = [];
    const recovered = await new TurnExecutor().execute(makeTask(), {
      model: new EarlyThrowStreamModel(),
      onOutput: async (text) => {
        outputs.push(text);
      },
    });
    expect(recovered.status).toBe("COMPLETE");
    expect(recovered.completion).toBe("recovered via invoke");
    expect(outputs).toEqual(["recovered via invoke"]);

    class LateThrowStreamModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        throw new Error("should not fall back after partials");
      }
      async *stream(): AsyncIterable<ModelStreamChunk> {
        yield { content: "par" };
        throw new Error("stream exploded mid-turn");
      }
    }
    const partialOutputs: string[] = [];
    const partial = await new TurnExecutor().execute(makeTask(), {
      model: new LateThrowStreamModel(),
      onOutput: async (text) => {
        partialOutputs.push(text);
      },
    });
    // Partials streamed something already: no silent restart - FAILED.
    expect(partial.status).toBe("FAILED");
    expect(partial.failure?.finishReason).toBe("PROVIDER_ERROR");
    expect(partialOutputs).toEqual(["par"]);
  });

  it("passes the third {signal} argument to invoke and stream (deadlines flow to the model)", async () => {
    const signals: Array<AbortSignal | null | undefined> = [];
    class SignalRecordingModel implements ChatModel {
      async invoke(
        _messages: ConversationMessage[],
        _tools: ToolSpec[],
        options?: { signal?: AbortSignal },
      ): Promise<ModelResponse> {
        signals.push(options?.signal ?? null);
        return { content: "done", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } };
      }
      async *stream(
        _messages: ConversationMessage[],
        _tools: ToolSpec[],
        options?: { signal?: AbortSignal },
      ): AsyncIterable<ModelStreamChunk> {
        signals.push(options?.signal ?? null);
        yield { content: "done" };
      }
    }

    const controller = new AbortController();
    await new TurnExecutor().execute(makeTask(), {
      model: new SignalRecordingModel(),
      modelSignal: controller.signal,
    });
    await new TurnExecutor().execute(makeTask(), {
      model: new SignalRecordingModel(),
      modelSignal: controller.signal,
      onOutput: async () => {}, // stream path
    });

    // Both paths received THE caller's signal (not a fabricated one).
    expect(signals[0]).toBe(controller.signal);
    expect(signals[1]).toBe(controller.signal);
  });

  it("without a modelSignal the third argument still threads {signal: undefined} (no compat overloads)", async () => {
    const signals: Array<AbortSignal | null | undefined> = [];
    class SignalRecordingModel implements ChatModel {
      async invoke(
        _messages: ConversationMessage[],
        _tools: ToolSpec[],
        options?: { signal?: AbortSignal },
      ): Promise<ModelResponse> {
        // Record the RAW options.signal (no null coercion) so the exact
        // threaded value is observable.
        signals.push(options?.signal);
        return { content: "done" };
      }
    }
    await new TurnExecutor().execute(makeTask(), { model: new SignalRecordingModel() });
    expect(signals).toHaveLength(1);
    expect(signals[0]).toBeUndefined();
  });

  it("onOutput is supported on the UN-streamed path too: the final invoke answer emits one provisional fragment", async () => {
    const outputs: string[] = [];
    const result = await new TurnExecutor().execute(makeTask(), {
      model: new ScriptedModel([{ content: "invoke answer" }]),
      onOutput: async (text) => {
        outputs.push(text);
      },
    });
    expect(result.status).toBe("COMPLETE");
    expect(outputs).toEqual(["invoke answer"]);
  });
});
