// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Provider resolution: turn the engine-supplied {@link ModelInfoWire} into a
 * ready-to-call {@link ChatModel}.
 *
 * The engine owns model routing and ships the provider, model id, endpoint,
 * credential reference, and inference parameters with each session. This
 * module maps that descriptor to the matching LangChain chat model and wraps
 * it in {@link LangChainChatModel}. Provider SDKs are imported lazily so a
 * deployment that only ever sees OpenAI never loads the Anthropic or Google
 * packages.
 *
 * Credentials (design 2026-09-16-credential-envelope-delivery.md §9/§10):
 * `apiKey` is gone from the wire — the model block carries `credentialRef`
 * and the caller injects a {@link CredentialResolver} backed by the
 * session's credential vault. Keyless local models carry no ref and are
 * constructed without an API key (existing behavior).
 */
import type { BaseChatModel } from "@langchain/core/language_models/chat_models";
import type { ChatModel } from "../executor/types.js";
import type { ModelInfoWire } from "../protocol/sessionTypes.js";
import { LangChainChatModel } from "./LangChainChatModel.js";

/** Resolves a `credentialRef` to its plaintext via the session credential vault. */
export type CredentialResolver = (ref: string) => string;

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
  resolveCredential?: CredentialResolver,
): Promise<ChatModel> {
  if (!info) {
    throw new Error("Cannot resolve a model: the task carried no model info");
  }
  return new LangChainChatModel(await resolveProviderModel(info, resolveCredential));
}

/** Resolve the underlying LangChain chat model for a provider descriptor. */
export async function resolveProviderModel(
  info: ModelInfoWire,
  resolveCredential?: CredentialResolver,
): Promise<BaseChatModel> {
  const provider = info.provider.toLowerCase();
  const isGoogle = provider === "google" || provider === "google_ai";
  if (!OPENAI_COMPATIBLE.has(provider) && provider !== "anthropic" && !isGoogle) {
    throw new Error(`Unsupported model provider: '${info.provider}'`);
  }
  const apiKey = resolveApiKey(provider, unwrapApiKey(info, resolveCredential));
  const params = info.parameters ?? {};

  if (OPENAI_COMPATIBLE.has(provider)) {
    const { ChatOpenAI } = await import("@langchain/openai");
    return new ChatOpenAI({
      model: info.modelId,
      apiKey,
      maxRetries: 0,
      ...(info.endpoint
        ? { configuration: { baseURL: info.endpoint } }
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
      ...(info.endpoint ? { anthropicApiUrl: info.endpoint } : {}),
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
}

/**
 * Pull the plaintext key for a credentialled model through the injected
 * vault resolver. `undefined` for a keyless model (resolver decides next).
 */
function unwrapApiKey(
  info: ModelInfoWire,
  resolveCredential?: CredentialResolver,
): string | undefined {
  const credentialRef = info.credentialRef;
  if (!credentialRef) {
    return undefined;
  }
  if (!resolveCredential) {
    throw new Error(
      `Provider '${info.provider}' references credential '${credentialRef}' but no vault resolver is wired`,
    );
  }
  return resolveCredential(credentialRef);
}

/** Pick the API key, allowing a placeholder for local no-auth providers. */
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
