// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, afterEach, vi } from "vitest";
import { EngineHttpClient, EngineHttpError } from "./httpClient.js";

const engineUrl = "http://engine.test";

function makeClient(): EngineHttpClient {
  return new EngineHttpClient({
    engineUrl,
    registrationKey: "myr_agent_test",
  });
}

/** Stub `fetch` and capture the single request it receives. */
function stubFetch(
  response: { status?: number; body: unknown },
): { calls: { url: string; init: RequestInit }[] } {
  const calls: { url: string; init: RequestInit }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(JSON.stringify(response.body), {
        status: response.status ?? 200,
        headers: { "Content-Type": "application/json" },
      });
    }),
  );
  return { calls };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("EngineHttpClient.retrieve", () => {
  const hit = {
    passage: "alpha",
    chunkId: "11111111-1111-1111-1111-111111111111",
    sourceId: "22222222-2222-2222-2222-222222222222",
    sourceName: "doc.md",
    locator: "doc.md#L1",
    score: 0.91,
  };

  it("posts to /agent/retrieve with the bearer token and parses hits", async () => {
    const { calls } = stubFetch({ body: [hit] });
    const hits = await makeClient().retrieve("tok", {
      knowledgeBaseId: "kb-1",
      query: "hello",
    });

    expect(hits).toEqual([hit]);
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(`${engineUrl}/api/v1/agent/retrieve`);
    const headers = calls[0].init.headers as Record<string, string>;
    expect(headers.Authorization).toBe("Bearer tok");
    const body = JSON.parse(calls[0].init.body as string);
    expect(body).toEqual({ knowledgeBaseId: "kb-1", query: "hello", topK: 5 });
  });

  it("includes audit context when supplied", async () => {
    const { calls } = stubFetch({ body: [] });
    await makeClient().retrieve("tok", {
      knowledgeBaseId: "kb-1",
      query: "hello",
      topK: 3,
      taskId: "task-9",
      attemptId: "attempt-2",
    });

    const body = JSON.parse(calls[0].init.body as string);
    expect(body).toEqual({
      knowledgeBaseId: "kb-1",
      query: "hello",
      topK: 3,
      taskId: "task-9",
      attemptId: "attempt-2",
    });
  });

  it("omits audit context when absent", async () => {
    const { calls } = stubFetch({ body: [] });
    await makeClient().retrieve("tok", {
      knowledgeBaseId: "kb-1",
      query: "hello",
    });

    const body = JSON.parse(calls[0].init.body as string);
    expect(body).not.toHaveProperty("taskId");
    expect(body).not.toHaveProperty("attemptId");
    expect(body).not.toHaveProperty("filters");
  });

  it("returns an empty list when the provider yields nothing", async () => {
    stubFetch({ body: [] });
    const hits = await makeClient().retrieve("tok", {
      knowledgeBaseId: "kb-1",
      query: "hello",
    });
    expect(hits).toEqual([]);
  });

  it("throws EngineHttpError on a 4xx response", async () => {
    stubFetch({ status: 400, body: { errorCode: "VALIDATION_ERROR" } });
    await expect(
      makeClient().retrieve("tok", { knowledgeBaseId: "kb-1", query: "" }),
    ).rejects.toBeInstanceOf(EngineHttpError);
  });
});
