// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * HTTP client for agent registration and token refresh.
 *
 * Ported from the Python SDK (`myrmec/agent/http_client.py` →
 * `EngineHttpClient`). Uses the Node-native `fetch` (Node 18+). Backs the
 * Supervisor's bootstrap (REQ-A-001) and token lifecycle (REQ-A-002).
 *
 * The engine speaks camelCase JSON (project convention); request/response
 * keys here match the wire exactly.
 */
import { hostname } from "node:os";

/** Result of a successful registration. */
export interface RegisterResponse {
  accessToken: string;
  refreshToken: string;
  /** Engine-assigned instance/agent id (UUID string). */
  instanceId: string;
}

/** Result of a successful token refresh. */
export interface RefreshResponse {
  accessToken: string;
  refreshToken: string;
}

/**
 * A single retrieval hit returned by `POST /api/v1/agent/retrieve`.
 *
 * Keys match the engine wire (camelCase) exactly, so hits are used as parsed.
 */
export interface RetrievalHit {
  passage: string;
  /** Chunk UUID (string). */
  chunkId: string;
  /** Source UUID (string). */
  sourceId: string;
  sourceName: string;
  locator: string;
  score: number;
}

/** Inputs for a {@link EngineHttpClient.retrieve} call. */
export interface RetrieveOptions {
  /** Target knowledge base UUID. */
  knowledgeBaseId: string;
  /** Free-text retrieval query. */
  query: string;
  /** Maximum number of hits to return (default 5). */
  topK?: number;
  /** Optional provider-specific filters. */
  filters?: Record<string, string>;
  /**
   * Optional audit context. When supplied, the engine records a `RETRIEVAL`
   * execution event (feature #32) capturing the query and returned
   * chunk/source IDs + scores so an AUDITOR can replay which knowledge fed an
   * answer. Must reference a real workflow task; omit for contextless
   * retrievals.
   */
  taskId?: string;
  /** Optional attempt id paired with {@link taskId}. */
  attemptId?: string;
}

export interface EngineHttpClientOptions {
  /** Base Engine URL, e.g. "http://localhost:8080". */
  engineUrl: string;
  /** Agent registration key (`myr_agent_…`). */
  registrationKey: string;
  /** SDK version reported to the Engine. */
  sdkVersion?: string;
  /** Per-request timeout in ms (default 30 000). */
  timeoutMs?: number;
  /** Optional metadata recorded with the registration. */
  metadata?: Record<string, unknown>;
}

/** Thrown when an Engine HTTP call returns a non-2xx status. */
export class EngineHttpError extends Error {
  constructor(
    readonly status: number,
    readonly path: string,
    readonly body: string,
  ) {
    super(`Engine ${path} failed: HTTP ${status}`);
    this.name = "EngineHttpError";
  }
}

export class EngineHttpClient {
  private readonly engineUrl: string;
  private readonly registrationKey: string;
  private readonly sdkVersion: string;
  private readonly timeoutMs: number;
  private readonly metadata?: Record<string, unknown>;

  constructor(options: EngineHttpClientOptions) {
    this.engineUrl = options.engineUrl.replace(/\/+$/, "");
    this.registrationKey = options.registrationKey;
    this.sdkVersion = options.sdkVersion ?? "0.1.0";
    this.timeoutMs = options.timeoutMs ?? 30_000;
    this.metadata = options.metadata;
  }

  /** Register this host with the Engine; returns the first token pair. */
  async register(): Promise<RegisterResponse> {
    const body: Record<string, unknown> = {
      hostname: hostname(),
      sdkVersion: this.sdkVersion,
    };
    if (this.metadata) {
      body.metadata = this.metadata;
    }

    const data = await this.post(
      "/api/v1/agent/auth/register",
      { "X-Registration-Key": this.registrationKey },
      body,
    );
    return {
      accessToken: data.accessToken as string,
      refreshToken: data.refreshToken as string,
      instanceId: data.instanceId as string,
    };
  }

  /** Exchange a refresh token for a fresh token pair. */
  async refresh(refreshToken: string): Promise<RefreshResponse> {
    const data = await this.post(
      "/api/v1/agent/auth/refresh",
      { Authorization: `Bearer ${refreshToken}` },
      undefined,
    );
    return {
      accessToken: data.accessToken as string,
      refreshToken: data.refreshToken as string,
    };
  }

  /**
   * Run a retrieval query against a knowledge base.
   *
   * Backs the `ctx.retrieve()` ergonomic helper that ships with the chat
   * runtime. Agents that want RAG grounding can also call this directly with
   * their current access token.
   *
   * The engine surfaces provider failures as an empty 200, so a misbehaving
   * knowledge base returns `[]` rather than aborting an agent run; only 4xx
   * (bad request / auth) responses throw {@link EngineHttpError}.
   *
   * @param accessToken Agent access token (from `register`/`refresh`).
   * @param options Retrieval inputs, including optional audit context.
   * @returns Ordered hits (highest-score first); empty when nothing matches.
   */
  async retrieve(
    accessToken: string,
    options: RetrieveOptions,
  ): Promise<RetrievalHit[]> {
    const body: Record<string, unknown> = {
      knowledgeBaseId: options.knowledgeBaseId,
      query: options.query,
      topK: options.topK ?? 5,
    };
    if (options.filters) {
      body.filters = options.filters;
    }
    if (options.taskId !== undefined) {
      body.taskId = options.taskId;
    }
    if (options.attemptId !== undefined) {
      body.attemptId = options.attemptId;
    }

    const data = await this.post<RetrievalHit[]>(
      "/api/v1/agent/retrieve",
      { Authorization: `Bearer ${accessToken}` },
      body,
    );
    return Array.isArray(data) ? data : [];
  }

  /**
   * Fetch the raw bytes of a conversation attachment (#103 Slice A).
   *
   * Backs native image inlining: the agent turns an image attachment's
   * `readContentPath` into a multimodal image part by GETting its bytes here.
   * Non-2xx responses throw {@link EngineHttpError} so the caller can degrade
   * to a text note rather than crash the turn.
   *
   * @param accessToken Agent access token (from `register`/`refresh`).
   * @param path Engine read path (e.g. `/api/v1/agent/conversations/…/content`).
   * @returns The attachment bytes.
   */
  async fetchAttachmentContent(
    accessToken: string,
    path: string,
  ): Promise<Uint8Array> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await fetch(`${this.engineUrl}${path}`, {
        method: "GET",
        headers: { Authorization: `Bearer ${accessToken}` },
        signal: controller.signal,
      });
      if (!response.ok) {
        const text = await response.text().catch(() => "");
        throw new EngineHttpError(response.status, path, text);
      }
      const buffer = await response.arrayBuffer();
      return new Uint8Array(buffer);
    } finally {
      clearTimeout(timer);
    }
  }

  /** Base URL the client targets. */
  get baseUrl(): string {
    return this.engineUrl;
  }

  private async post<T = Record<string, unknown>>(
    path: string,
    headers: Record<string, string>,
    body: Record<string, unknown> | undefined,
  ): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await fetch(`${this.engineUrl}${path}`, {
        method: "POST",
        headers: {
          ...headers,
          ...(body ? { "Content-Type": "application/json" } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      });

      const text = await response.text();
      if (!response.ok) {
        throw new EngineHttpError(response.status, path, text);
      }
      return (text ? JSON.parse(text) : {}) as T;
    } finally {
      clearTimeout(timer);
    }
  }
}
