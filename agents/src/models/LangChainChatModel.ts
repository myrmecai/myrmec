// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * LangChain-backed implementation of the {@link ChatModel} seam.
 *
 * Adapts the executor's provider-agnostic transcript ({@link ConversationMessage})
 * to a LangChain `BaseChatModel` and normalises the reply — text, tool calls,
 * and token usage — back into a {@link ModelResponse}. Because LangChain unifies
 * the tool-calling wire formats of OpenAI, Anthropic, and Google behind one
 * `AIMessage.tool_calls` shape, the same adapter serves every provider; only
 * the concrete model handed in differs (see `resolveModel`).
 */
import {
  AIMessage,
  HumanMessage,
  SystemMessage,
  ToolMessage,
  type BaseMessage,
  type MessageContent,
} from "@langchain/core/messages";
import type { BaseChatModel } from "@langchain/core/language_models/chat_models";
import type {
  ChatModel,
  ConversationMessage,
  ModelResponse,
  ModelStreamChunk,
  ModelToolCall,
  TokenUsage,
  ToolSpec,
} from "../executor/types.js";

/** OpenAI-style function tool definition accepted by every provider adapter. */
interface FunctionToolDefinition {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
}

export class LangChainChatModel implements ChatModel {
  constructor(private readonly model: BaseChatModel) {}

  async invoke(
    messages: ConversationMessage[],
    tools: ToolSpec[],
  ): Promise<ModelResponse> {
    const lcMessages = messages.map(toLangChainMessage);
    const runnable = this.bind(tools);

    const reply = await runnable.invoke(lcMessages);

    return {
      content: extractText(reply.content),
      toolCalls: extractToolCalls(reply.tool_calls),
      usage: extractUsage(reply.usage_metadata),
    };
  }

  async *stream(
    messages: ConversationMessage[],
    tools: ToolSpec[],
  ): AsyncIterable<ModelStreamChunk> {
    const lcMessages = messages.map(toLangChainMessage);
    const runnable = this.bind(tools);

    const stream = await runnable.stream(lcMessages);
    for await (const chunk of stream) {
      const content = extractText(chunk.content);
      const usage = extractUsage(chunk.usage_metadata);
      const toolCalls = extractToolCalls(chunk.tool_calls);
      // Skip wholly empty heartbeat chunks; surface text, usage, and/or tool calls.
      if (content.length === 0 && usage === undefined && toolCalls === undefined) {
        continue;
      }
      yield {
        ...(content.length > 0 ? { content } : {}),
        ...(usage !== undefined ? { usage } : {}),
        ...(toolCalls !== undefined ? { toolCalls } : {}),
      };
    }
  }

  /** Bind tools to the model when any are offered and the provider supports
   * it; otherwise return the bare model. Shared by {@link invoke} and
   * {@link stream}. */
  private bind(tools: ToolSpec[]) {
    return tools.length > 0 && typeof this.model.bindTools === "function"
      ? this.model.bindTools(tools.map(toToolDefinition))
      : this.model;
  }
}

/** Map one transcript message to its LangChain equivalent. */
function toLangChainMessage(message: ConversationMessage): BaseMessage {
  switch (message.role) {
    case "system":
      return new SystemMessage({ content: message.content });
    case "assistant":
      return new AIMessage({
        content: message.content,
        tool_calls: (message.toolCalls ?? []).map((call) => ({
          id: call.id,
          name: call.name,
          args: call.args,
          type: "tool_call" as const,
        })),
      });
    case "tool":
      return new ToolMessage({
        content: message.content,
        tool_call_id: message.toolCallId ?? "",
      });
    case "user":
    default:
      return new HumanMessage({ content: message.content });
  }
}

/** Render a tool spec as the OpenAI-style function definition LangChain binds. */
function toToolDefinition(spec: ToolSpec): FunctionToolDefinition {
  return {
    type: "function",
    function: {
      name: spec.name,
      description: spec.description ?? "",
      parameters: spec.parameters ?? { type: "object", properties: {} },
    },
  };
}

/** Flatten LangChain message content (string or content blocks) to plain text. */
function extractText(content: MessageContent): string {
  if (typeof content === "string") {
    return content;
  }
  return content
    .map((block) => {
      if (typeof block === "string") {
        return block;
      }
      if (block && typeof block === "object" && "text" in block) {
        const text = (block as { text?: unknown }).text;
        return typeof text === "string" ? text : "";
      }
      return "";
    })
    .join("");
}

/** Normalise LangChain tool calls to the executor's {@link ModelToolCall}. */
function extractToolCalls(
  calls: { id?: string; name: string; args: Record<string, unknown> }[] | undefined,
): ModelToolCall[] | undefined {
  if (!calls || calls.length === 0) {
    return undefined;
  }
  return calls.map((call, index) => ({
    id: call.id ?? `call_${index}`,
    name: call.name,
    args: call.args ?? {},
  }));
}

/** Translate LangChain usage metadata to the executor's {@link TokenUsage}. */
function extractUsage(
  usage:
    | { input_tokens?: number; output_tokens?: number; total_tokens?: number }
    | undefined,
): TokenUsage | undefined {
  if (!usage) {
    return undefined;
  }
  return {
    promptTokens: usage.input_tokens,
    completionTokens: usage.output_tokens,
    totalTokens: usage.total_tokens,
  };
}
