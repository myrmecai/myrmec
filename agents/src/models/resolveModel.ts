// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Provider resolution: turn the engine-supplied {@link ModelInfoWire} into a
 * ready-to-call {@link ChatModel}.
 *
 * The engine owns model routing and ships the provider, model id, endpoint,
 * credentials, and inference parameters with each task. This module maps that
 * descriptor to the matching LangChain chat model and wraps it in
 * {@link LangChainChatModel}. Provider SDKs are imported lazily so a deployment
 * that only ever sees OpenAI never loads the Anthropic or Google packages.
 */
import type { BaseChatModel } from "@langchain/core/language_models/chat_models";
import type { ChatModel } from "../executor/types.js";
import type { ModelInfoWire } from "../protocol/taskFrames.js";
import { LangChainChatModel } from "./LangChainChatModel.js";

/** OpenAI and every wire-compatible provider served by `ChatOpenAI`. */
const OPENAI_COMPATIBLE = new Set([
  "openai",
  "github_models",
  "azure_openai",
  "openrouter",
  "together_ai",
  "groq",
  "fireworks",
  "generic",
  "ollama",
  "localai",
  "vllm",
  "tgi",
  "llama_cpp",
]);

/** Providers that typically run without authentication (local servers). */
const NO_AUTH = new Set(["generic", "ollama", "localai", "vllm", "tgi", "llama_cpp"]);

/** Resolve a {@link ChatModel} from the engine's model descriptor. */
export async function resolveChatModel(
  info: ModelInfoWire | undefined,
): Promise<ChatModel> {
  if (!info) {
    throw new Error("Cannot resolve a model: the task carried no model info");
  }
  return new LangChainChatModel(await resolveProviderModel(info));
}

/** Resolve the underlying LangChain chat model for a provider descriptor. */
export async function resolveProviderModel(
  info: ModelInfoWire,
): Promise<BaseChatModel> {
  const provider = info.provider.toLowerCase();
  const isGoogle = provider === "google" || provider === "google_ai";
  if (!OPENAI_COMPATIBLE.has(provider) && provider !== "anthropic" && !isGoogle) {
    throw new Error(`Unsupported model provider: '${info.provider}'`);
  }
  const apiKey = resolveApiKey(provider, info.apiKey ?? undefined);
  const params = info.parameters ?? {};

  if (OPENAI_COMPATIBLE.has(provider)) {
    const { ChatOpenAI } = await import("@langchain/openai");
    return new ChatOpenAI({
      model: info.modelId,
      apiKey,
      maxRetries: 0,
      ...(info.apiEndpoint
        ? { configuration: { baseURL: info.apiEndpoint } }
        : {}),
      ...params,
    } as ConstructorParameters<typeof ChatOpenAI>[0]);
  }

  if (provider === "anthropic") {
    const { ChatAnthropic } = await import("@langchain/anthropic");
    return new ChatAnthropic({
      model: info.modelId,
      apiKey,
      maxRetries: 0,
      ...(info.apiEndpoint ? { anthropicApiUrl: info.apiEndpoint } : {}),
      ...params,
    } as ConstructorParameters<typeof ChatAnthropic>[0]);
  }

  if (provider === "google" || provider === "google_ai") {
    const { ChatGoogleGenerativeAI } = await import("@langchain/google-genai");
    return new ChatGoogleGenerativeAI({
      model: info.modelId,
      apiKey,
      maxRetries: 0,
      ...params,
    } as ConstructorParameters<typeof ChatGoogleGenerativeAI>[0]);
  }

  throw new Error(`Unsupported model provider: '${info.provider}'`);
}/** Pick the API key, allowing a placeholder for local no-auth providers. */
function resolveApiKey(provider: string, apiKey: string | undefined): string {
  if (apiKey) {
    return apiKey;
  }
  if (NO_AUTH.has(provider)) {
    // Local OpenAI-compatible servers ignore the key but the SDK requires one.
    return "local";
  }
  throw new Error(`Provider '${provider}' requires an API key`);
}
