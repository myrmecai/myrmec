// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect } from "vitest";
import {
  AIMessage,
  AIMessageChunk,
  type BaseMessage,
} from "@langchain/core/messages";
import type { BaseChatModel } from "@langchain/core/language_models/chat_models";
import { LangChainChatModel } from "./LangChainChatModel.js";
import type { ConversationMessage, ToolSpec } from "../executor/types.js";

/** A fake LangChain model that records what it received and returns a fixed
 * reply, exercising both the `bindTools` and plain `invoke` paths. */
function fakeModel(reply: AIMessage) {
  const seen: { messages: BaseMessage[]; boundTools: unknown[] | null } = {
    messages: [],
    boundTools: null,
  };
  const model = {
    bindTools(tools: unknown[]) {
      seen.boundTools = tools;
      return {
        invoke: async (messages: BaseMessage[]) => {
          seen.messages = messages;
          return reply;
        },
      };
    },
    invoke: async (messages: BaseMessage[]) => {
      seen.messages = messages;
      return reply;
    },
  } as unknown as BaseChatModel;
  return { model, seen };
}

describe("LangChainChatModel", () => {
  it("returns a final answer with normalised usage", async () => {
    const reply = new AIMessage({
      content: "the answer",
      usage_metadata: { input_tokens: 10, output_tokens: 4, total_tokens: 14 },
    });
    const { model } = fakeModel(reply);
    const adapter = new LangChainChatModel(model);

    const res = await adapter.invoke(
      [{ role: "user", content: "hi" }],
      [],
    );

    expect(res.content).toBe("the answer");
    expect(res.toolCalls).toBeUndefined();
    expect(res.usage).toEqual({
      promptTokens: 10,
      completionTokens: 4,
      totalTokens: 14,
    });
  });

  it("normalises tool calls from the reply", async () => {
    const reply = new AIMessage({
      content: "",
      tool_calls: [
        { id: "c1", name: "search", args: { q: "x" }, type: "tool_call" },
      ],
    });
    const { model, seen } = fakeModel(reply);
    const adapter = new LangChainChatModel(model);

    const tools: ToolSpec[] = [
      { name: "search", description: "find", parameters: { type: "object" } },
    ];
    const res = await adapter.invoke([{ role: "user", content: "hi" }], tools);

    expect(res.toolCalls).toEqual([
      { id: "c1", name: "search", args: { q: "x" } },
    ]);
    // Tools are bound as OpenAI-style function definitions.
    expect(seen.boundTools).toEqual([
      {
        type: "function",
        function: {
          name: "search",
          description: "find",
          parameters: { type: "object" },
        },
      },
    ]);
  });

  it("maps the transcript to the right LangChain message types", async () => {
    const reply = new AIMessage({ content: "ok" });
    const { model, seen } = fakeModel(reply);
    const adapter = new LangChainChatModel(model);

    const transcript: ConversationMessage[] = [
      { role: "system", content: "sys" },
      { role: "user", content: "hello" },
      {
        role: "assistant",
        content: "calling",
        toolCalls: [{ id: "c1", name: "search", args: { q: "x" } }],
      },
      { role: "tool", content: "result", toolCallId: "c1" },
    ];

    await adapter.invoke(transcript, []);

    expect(seen.messages.map((m) => m.getType())).toEqual([
      "system",
      "human",
      "ai",
      "tool",
    ]);
    const ai = seen.messages[2] as AIMessage;
    expect(ai.tool_calls?.[0]).toMatchObject({ id: "c1", name: "search" });
  });

  it("does not bind tools when none are offered", async () => {
    const reply = new AIMessage({ content: "ok" });
    const { model, seen } = fakeModel(reply);
    const adapter = new LangChainChatModel(model);

    await adapter.invoke([{ role: "user", content: "hi" }], []);

    expect(seen.boundTools).toBeNull();
  });

  it("flattens array content blocks to text", async () => {
    const reply = new AIMessage({
      content: [
        { type: "text", text: "part one " },
        { type: "text", text: "part two" },
      ],
    });
    const { model } = fakeModel(reply);
    const adapter = new LangChainChatModel(model);

    const res = await adapter.invoke([{ role: "user", content: "hi" }], []);
    expect(res.content).toBe("part one part two");
  });

  it("streams text chunks and surfaces final usage", async () => {
    const chunks = [
      new AIMessageChunk({ content: "Hel" }),
      new AIMessageChunk({ content: "lo" }),
      new AIMessageChunk({
        content: "",
        usage_metadata: { input_tokens: 3, output_tokens: 2, total_tokens: 5 },
      }),
    ];
    let seen: BaseMessage[] = [];
    const model = {
      stream: async (messages: BaseMessage[]) => {
        seen = messages;
        return (async function* () {
          for (const c of chunks) {
            yield c;
          }
        })();
      },
    } as unknown as BaseChatModel;
    const adapter = new LangChainChatModel(model);

    const out: { content?: string; usage?: unknown }[] = [];
    for await (const chunk of adapter.stream!(
      [{ role: "user", content: "hi" }],
      [],
    )) {
      out.push(chunk);
    }

    // Empty heartbeat carrying no text and no usage is skipped; the final
    // usage-only chunk is surfaced.
    expect(out).toEqual([
      { content: "Hel" },
      { content: "lo" },
      { usage: { promptTokens: 3, completionTokens: 2, totalTokens: 5 } },
    ]);
    expect(seen.map((m) => m.getType())).toEqual(["human"]);
  });
});
