// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect } from "vitest";
import { ChatOpenAI } from "@langchain/openai";
import { ChatAnthropic } from "@langchain/anthropic";
import { ChatGoogleGenerativeAI } from "@langchain/google-genai";
import { resolveChatModel, resolveProviderModel } from "./resolveModel.js";
import { LangChainChatModel } from "./LangChainChatModel.js";
import { modelInfoSchema } from "../protocol/unifiedFrames.js";

/** Build a ModelInfoWire through the schema so defaults apply. */
function modelInfo(overrides: Record<string, unknown>) {
  return modelInfoSchema.parse({ provider: "openai", modelId: "gpt-4o", ...overrides });
}

describe("resolveChatModel", () => {
  it("throws when no model info is supplied", async () => {
    await expect(resolveChatModel(undefined)).rejects.toThrow(/no model info/i);
  });

  it("wraps a resolved provider model in the adapter", async () => {
    const model = await resolveChatModel(
      modelInfo({ provider: "openai", modelId: "gpt-4o", credentialRef: "model-provider-token" }),
      (ref) => (ref === "model-provider-token" ? "sk-test" : ""),
    );
    expect(model).toBeInstanceOf(LangChainChatModel);
  });
});

describe("resolveProviderModel", () => {
  it("resolves OpenAI", async () => {
    const m = await resolveProviderModel(
      modelInfo({ provider: "openai", modelId: "gpt-4o", credentialRef: "model-provider-token" }),
      () => "sk-test",
    );
    expect(m).toBeInstanceOf(ChatOpenAI);
    expect((m as ChatOpenAI).model).toBe("gpt-4o");
  });

  it("resolves an OpenAI-compatible provider with a custom endpoint", async () => {
    const m = await resolveProviderModel(
      modelInfo({
        provider: "groq",
        modelId: "llama-3.1-70b",
        credentialRef: "model-provider-token",
        apiEndpoint: "https://api.groq.com/openai/v1",
      }),
      () => "gsk-test",
    );
    expect(m).toBeInstanceOf(ChatOpenAI);
  });

  it("resolves a local no-auth provider without an API key", async () => {
    const m = await resolveProviderModel(
      modelInfo({ provider: "ollama", modelId: "llama3", credentialRef: null }),
    );
    expect(m).toBeInstanceOf(ChatOpenAI);
  });

  it("rejects a credentialled provider with no API key", async () => {
    await expect(
      resolveProviderModel(
        modelInfo({ provider: "openai", modelId: "gpt-4o", credentialRef: null }),
      ),
    ).rejects.toThrow(/requires an API key/i);
  });

  it("rejects a credentialRef when no vault resolver is wired", async () => {
    await expect(
      resolveProviderModel(
        modelInfo({ provider: "openai", modelId: "gpt-4o", credentialRef: "model-provider-token" }),
      ),
    ).rejects.toThrow(/no vault resolver is wired/i);
  });

  it("propagates the resolver's UNKNOWN_REF failure", async () => {
    const err = new Error("credentialRef 'nope' was never unwrapped");
    await expect(
      resolveProviderModel(
        modelInfo({ provider: "openai", modelId: "gpt-4o", credentialRef: "nope" }),
        () => {
          throw err;
        },
      ),
    ).rejects.toThrow(/never unwrapped/i);
  });

  it("resolves Anthropic", async () => {
    const m = await resolveProviderModel(
      modelInfo({ provider: "anthropic", modelId: "claude-3-5-sonnet", credentialRef: "model-provider-token" }),
      () => "sk-ant",
    );
    expect(m).toBeInstanceOf(ChatAnthropic);
  });

  it("resolves Google", async () => {
    const m = await resolveProviderModel(
      modelInfo({ provider: "google", modelId: "gemini-1.5-pro", credentialRef: "model-provider-token" }),
      () => "g-test",
    );
    expect(m).toBeInstanceOf(ChatGoogleGenerativeAI);
  });

  it("rejects an unsupported provider", async () => {
    await expect(
      resolveProviderModel(modelInfo({ provider: "cohere", modelId: "command" })),
    ).rejects.toThrow(/unsupported model provider/i);
  });
});
