// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import {
  ConversationDispatcher,
  assembleConversationMessages,
  buildAttachmentContext,
  type ConversationTurnContext,
} from "./ConversationDispatcher.js";
import type {
  ChatModel,
  ConversationMessage,
  MessageContentPart,
  ModelResponse,
  ModelStreamChunk,
} from "./types.js";
import type { Envelope } from "../protocol/envelope.js";
import type { EngineHttpClient } from "../transport/httpClient.js";
import { MessageType } from "../protocol/messages.js";
import {
  conversationTurnAssignPayloadSchema,
  messageComplete,
} from "../protocol/conversationFrames.js";

function recorder() {
  const frames: Envelope[] = [];
  const send = (frame: Envelope) => {
    frames.push(frame);
    return Promise.resolve();
  };
  const ofType = (type: string) => frames.filter((f) => f.type === type);
  const typesSent = () => frames.map((f) => f.type);
  return { frames, send, ofType, typesSent };
}

function turnPayload(overrides: Record<string, unknown> = {}) {
  return {
    conversationId: "c1",
    projectId: "p1",
    agentId: "a1",
    assistantSequenceNo: 5,
    userMessage: "hello",
    model: { provider: "openai", modelId: "gpt-4o" },
    ...overrides,
  };
}

/** A model that streams two text chunks then a usage-only chunk. */
class StreamModel implements ChatModel {
  async invoke(): Promise<ModelResponse> {
    return { content: "Hello" };
  }
  async *stream(): AsyncIterable<ModelStreamChunk> {
    yield { content: "Hel" };
    yield { content: "lo" };
    yield { usage: { completionTokens: 5 } };
  }
}

/** A model with no streaming support — exercises the invoke() fallback. */
class InvokeOnlyModel implements ChatModel {
  async invoke(): Promise<ModelResponse> {
    return { content: "single shot", usage: { completionTokens: 3 } };
  }
}

/** A streaming model that emits one chunk, then blocks on a gate the test
 * controls before emitting the next — lets a cancel land mid-stream. */
class GatedStreamModel implements ChatModel {
  release!: () => void;
  private readonly gate = new Promise<void>((r) => {
    this.release = r;
  });
  async invoke(): Promise<ModelResponse> {
    return { content: "Hello" };
  }
  async *stream(): AsyncIterable<ModelStreamChunk> {
    yield { content: "Hel" };
    await this.gate;
    yield { content: "lo" };
  }
}

describe("ConversationDispatcher", () => {
  it("streams deltas then completes with full text, modelCode, tokenCount", async () => {
    const { send, ofType, frames } = recorder();
    const d = new ConversationDispatcher({ send, model: new StreamModel() });

    d.handleTurnAssign(turnPayload());

    await vi.waitFor(() =>
      expect(frames.map((f) => f.type)).toContain(MessageType.MESSAGE_COMPLETE),
    );

    const deltas = ofType(MessageType.MESSAGE_DELTA);
    expect(deltas.map((f) => (f.payload as { content: string }).content)).toEqual(
      ["Hel", "lo"],
    );
    expect(deltas.map((f) => (f.payload as { deltaIndex: number }).deltaIndex)).toEqual(
      [0, 1],
    );
    const complete = ofType(MessageType.MESSAGE_COMPLETE)[0];
    expect(complete.payload).toMatchObject({
      conversationId: "c1",
      sequenceNo: 5,
      content: "Hello",
      modelCode: "openai/gpt-4o",
      tokenCount: 5,
    });
  });

  it("falls back to invoke() as one delta when the model cannot stream", async () => {
    const { send, ofType, frames } = recorder();
    const d = new ConversationDispatcher({ send, model: new InvokeOnlyModel() });

    d.handleTurnAssign(turnPayload());

    await vi.waitFor(() =>
      expect(frames.map((f) => f.type)).toContain(MessageType.MESSAGE_COMPLETE),
    );

    const deltas = ofType(MessageType.MESSAGE_DELTA);
    expect(deltas).toHaveLength(1);
    expect((deltas[0].payload as { content: string }).content).toBe("single shot");
    expect(ofType(MessageType.MESSAGE_COMPLETE)[0].payload).toMatchObject({
      content: "single shot",
      tokenCount: 3,
    });
  });

  it("drops a malformed conversation.turn.assign", async () => {
    const { send, frames } = recorder();
    const d = new ConversationDispatcher({ send, model: new StreamModel() });

    d.handleTurnAssign({ conversationId: "c1" }); // missing required fields

    await new Promise((r) => setTimeout(r, 10));
    expect(frames).toHaveLength(0);
  });

  it("routes an approval.decision to a handler awaiting it mid-turn", async () => {
    const { send, ofType, frames } = recorder();

    const handler = async (ctx: ConversationTurnContext) => {
      const outcome = await ctx.requestApproval({ content: "Delete file?" });
      await ctx.send(
        messageComplete({
          conversationId: ctx.conversationId,
          sequenceNo: ctx.assistantSequenceNo,
          content: outcome.approved ? "done" : "aborted",
        }),
      );
    };

    const d = new ConversationDispatcher({
      send,
      model: new StreamModel(),
      handler,
    });

    d.handleTurnAssign(turnPayload());

    // The handler blocks on the approval; the request frame goes out first.
    await vi.waitFor(() =>
      expect(frames.map((f) => f.type)).toContain(MessageType.APPROVAL_REQUEST),
    );
    expect(d.pendingApprovals).toBe(1);

    const req = ofType(MessageType.APPROVAL_REQUEST)[0];
    const clientRequestId = (req.payload as { clientRequestId: string })
      .clientRequestId;

    d.handleApprovalDecision({
      conversationId: "c1",
      clientRequestId,
      decision: "APPROVED",
    });

    await vi.waitFor(() =>
      expect(frames.map((f) => f.type)).toContain(MessageType.MESSAGE_COMPLETE),
    );
    expect(ofType(MessageType.MESSAGE_COMPLETE)[0].payload).toMatchObject({
      content: "done",
    });
    expect(d.pendingApprovals).toBe(0);
  });

  it("requires a model or resolver", () => {
    expect(() => new ConversationDispatcher({ send: async () => {} })).toThrow(
      /requires either a fixed `model` or a `resolveModel`/,
    );
  });

  it("aborts an in-flight turn on conversation.turn.cancel and emits task.cancelled with the partial", async () => {
    const { send, ofType, frames } = recorder();
    const model = new GatedStreamModel();
    const d = new ConversationDispatcher({ send, model });

    d.handleTurnAssign(turnPayload());

    // First delta lands, then the model blocks on its gate.
    await vi.waitFor(() =>
      expect(frames.map((f) => f.type)).toContain(MessageType.MESSAGE_DELTA),
    );

    // Cancel the turn, then release the gate so the stream loop resumes and
    // observes the abort before emitting another delta.
    d.handleTurnCancel({ conversationId: "c1" });
    model.release();

    await vi.waitFor(() =>
      expect(frames.map((f) => f.type)).toContain(MessageType.TASK_CANCELLED),
    );

    const cancelled = ofType(MessageType.TASK_CANCELLED)[0];
    expect(cancelled.payload).toMatchObject({
      conversationId: "c1",
      sequenceNo: 5,
      partialContent: "Hel",
      reason: "user_request",
    });
    // The turn never completed.
    expect(frames.map((f) => f.type)).not.toContain(
      MessageType.MESSAGE_COMPLETE,
    );
  });

  it("ignores a cancel for a conversation with no in-flight turn", () => {
    const { send } = recorder();
    const d = new ConversationDispatcher({ send, model: new StreamModel() });
    // Must not throw.
    expect(() => d.handleTurnCancel({ conversationId: "nope" })).not.toThrow();
  });
});

describe("assembleConversationMessages", () => {
  it("combines system prompt and pinned facts, maps history, appends the user message", () => {
    const messages = assembleConversationMessages(
      conversationTurnAssignPayloadSchema.parse(
        turnPayload({
          systemPrompt: "You are helpful.",
          pinnedFacts: "The sky is blue.",
          history: [
            { role: "USER", content: "earlier q", sequenceNo: 1 },
            { role: "ASSISTANT", content: "earlier a", sequenceNo: 2 },
          ],
        }),
      ),
    );

    expect(messages).toEqual([
      { role: "system", content: "You are helpful.\n\nThe sky is blue." },
      { role: "user", content: "earlier q" },
      { role: "assistant", content: "earlier a" },
      { role: "user", content: "hello" },
    ]);
  });

  it("omits the system message when neither prompt nor pinned facts are set", () => {
    const messages = assembleConversationMessages(
      conversationTurnAssignPayloadSchema.parse(turnPayload()),
    );
    expect(messages).toEqual([{ role: "user", content: "hello" }]);
  });

  it("appends inline attachment text to the user message", () => {
    const messages = assembleConversationMessages(
      conversationTurnAssignPayloadSchema.parse(
        turnPayload({
          attachments: [
            {
              id: "att-1",
              filename: "notes.txt",
              mediaType: "text/plain",
              sizeBytes: 11,
              inlineText: "hello world",
            },
          ],
        }),
      ),
    );
    expect(messages).toEqual([
      {
        role: "user",
        content:
          "hello\n\n--- Attached file: notes.txt (text/plain) ---\nhello world",
      },
    ]);
  });
});

describe("buildAttachmentContext", () => {
  it("returns empty string when there are no attachments", () => {
    expect(buildAttachmentContext(undefined)).toBe("");
    expect(buildAttachmentContext([])).toBe("");
  });

  it("renders images and binaries as metadata notes", () => {
    const ctx = buildAttachmentContext([
      {
        id: "img-1",
        filename: "diagram.png",
        mediaType: "image/png",
        sizeBytes: 2048,
        image: true,
        inlineTextOmittedBySize: false,
        inlineTextOmittedByBudget: false,
      },
      {
        id: "bin-1",
        filename: "report.pdf",
        mediaType: "application/pdf",
        sizeBytes: 4096,
        image: false,
        inlineTextOmittedBySize: false,
        inlineTextOmittedByBudget: false,
      },
    ]);
    expect(ctx).toBe(
      "--- Attached image: diagram.png (image/png, 2048 bytes) ---\n\n" +
        "--- Attached file: report.pdf (application/pdf, 4096 bytes; " +
        "content not inlined, fetch by id bin-1 if needed) ---",
    );
  });
});

describe("ConversationDispatcher.ctx.retrieve", () => {
  // Mock HTTP client for testing.
  class MockHttpClient {
    async retrieve(token: string, options: unknown) {
      return [
        {
          passage: "The capital of France is Paris.",
          chunkId: "chunk-1",
          sourceId: "source-1",
          sourceName: "geography.txt",
          locator: "page 5",
          score: 0.95,
        },
        {
          passage: "Paris has many museums.",
          chunkId: "chunk-2",
          sourceId: "source-1",
          sourceName: "geography.txt",
          locator: "page 6",
          score: 0.88,
        },
      ];
    }
  }

  class FailingHttpClient {
    async retrieve() {
      throw new Error("Network error");
    }
  }

  it("calls httpClient.retrieve with the correct parameters", async () => {
    const { send } = recorder();
    const httpClient = new MockHttpClient();
    const retrieveSpy = vi.spyOn(httpClient, "retrieve");

    const handler = async (ctx: ConversationTurnContext) => {
      const hits = await ctx.retrieve("kb-1", "Where is Paris?");
      expect(hits).toHaveLength(2);
      await ctx.send(
        messageComplete({
          conversationId: ctx.conversationId,
          sequenceNo: ctx.assistantSequenceNo,
          content: `Found ${hits.length} results`,
        }),
      );
    };

    const d = new ConversationDispatcher({
      send,
      model: new StreamModel(),
      handler,
      httpClient: httpClient as any,
      agentAccessToken: "token-123",
    });

    d.handleTurnAssign(turnPayload());

    await vi.waitFor(() =>
      expect(retrieveSpy).toHaveBeenCalledWith("token-123", {
        knowledgeBaseId: "kb-1",
        query: "Where is Paris?",
        topK: undefined,
        filters: undefined,
        taskId: undefined,
        attemptId: undefined,
      }),
    );
  });

  it("passes optional topK, filters, and audit parameters", async () => {
    const { send } = recorder();
    const httpClient = new MockHttpClient();
    const retrieveSpy = vi.spyOn(httpClient, "retrieve");

    const handler = async (ctx: ConversationTurnContext) => {
      await ctx.retrieve("kb-1", "Paris", {
        topK: 10,
        filters: { lang: "en" },
        taskId: "task-1",
        attemptId: "attempt-1",
      });
      await ctx.send(
        messageComplete({
          conversationId: ctx.conversationId,
          sequenceNo: ctx.assistantSequenceNo,
          content: "done",
        }),
      );
    };

    const d = new ConversationDispatcher({
      send,
      model: new StreamModel(),
      handler,
      httpClient: httpClient as any,
      agentAccessToken: "token-456",
    });

    d.handleTurnAssign(turnPayload());

    await vi.waitFor(() =>
      expect(retrieveSpy).toHaveBeenCalledWith("token-456", {
        knowledgeBaseId: "kb-1",
        query: "Paris",
        topK: 10,
        filters: { lang: "en" },
        taskId: "task-1",
        attemptId: "attempt-1",
      }),
    );
  });

  it("returns hits ordered by score (highest first)", async () => {
    const { send } = recorder();
    const httpClient = new MockHttpClient();

    let retrievedHits;
    const handler = async (ctx: ConversationTurnContext) => {
      retrievedHits = await ctx.retrieve("kb-1", "query");
      await ctx.send(
        messageComplete({
          conversationId: ctx.conversationId,
          sequenceNo: ctx.assistantSequenceNo,
          content: "done",
        }),
      );
    };

    const d = new ConversationDispatcher({
      send,
      model: new StreamModel(),
      handler,
      httpClient: httpClient as any,
      agentAccessToken: "token-789",
    });

    d.handleTurnAssign(turnPayload());

    await vi.waitFor(
      () =>
        expect(retrievedHits).toBeDefined() && retrievedHits.length === 2,
    );

    expect(retrievedHits![0].score).toBe(0.95);
    expect(retrievedHits![1].score).toBe(0.88);
    expect(retrievedHits![0].passage).toBe(
      "The capital of France is Paris.",
    );
  });

  it("throws when httpClient is not configured", async () => {
    const { send, ofType } = recorder();
    const handler = async (ctx: ConversationTurnContext) => {
      try {
        await ctx.retrieve("kb-1", "query");
        throw new Error("Should have thrown");
      } catch (err) {
        const message = (err as Error).message;
        expect(message).toContain(
          "Knowledge base retrieval not configured",
        );
        await ctx.send(
          messageComplete({
            conversationId: ctx.conversationId,
            sequenceNo: ctx.assistantSequenceNo,
            content: "handled error",
          }),
        );
      }
    };

    const d = new ConversationDispatcher({
      send,
      model: new StreamModel(),
      handler,
      // No httpClient or agentAccessToken provided
    });

    d.handleTurnAssign(turnPayload());

    await vi.waitFor(() =>
      expect(ofType(MessageType.MESSAGE_COMPLETE)).toHaveLength(1),
    );
    const complete = ofType(MessageType.MESSAGE_COMPLETE)[0];
    expect((complete.payload as any).content).toBe("handled error");
  });

  it("propagates retrieval errors to the handler", async () => {
    const { send } = recorder();
    const httpClient = new FailingHttpClient();

    let caughtError;
    const handler = async (ctx: ConversationTurnContext) => {
      try {
        await ctx.retrieve("kb-1", "query");
      } catch (err) {
        caughtError = err;
      }
      await ctx.send(
        messageComplete({
          conversationId: ctx.conversationId,
          sequenceNo: ctx.assistantSequenceNo,
          content: "error handled",
        }),
      );
    };

    const d = new ConversationDispatcher({
      send,
      model: new StreamModel(),
      handler,
      httpClient: httpClient as any,
      agentAccessToken: "token",
    });

    d.handleTurnAssign(turnPayload());

    await vi.waitFor(() => expect(caughtError).toBeDefined());
    expect((caughtError as Error).message).toBe("Network error");
  });

  it("returns empty array when no hits match", async () => {
    const { send } = recorder();

    class EmptyResultsHttpClient {
      async retrieve() {
        return [];
      }
    }

    let results;
    const handler = async (ctx: ConversationTurnContext) => {
      results = await ctx.retrieve("kb-1", "nonexistent");
      await ctx.send(
        messageComplete({
          conversationId: ctx.conversationId,
          sequenceNo: ctx.assistantSequenceNo,
          content: `got ${results.length} results`,
        }),
      );
    };

    const d = new ConversationDispatcher({
      send,
      model: new StreamModel(),
      handler,
      httpClient: new EmptyResultsHttpClient() as any,
      agentAccessToken: "token",
    });

    d.handleTurnAssign(turnPayload());

    await vi.waitFor(() => expect(results).toBeDefined());
    expect(results).toEqual([]);
  });

  it("includes source attribution metadata in each hit", async () => {
    const { send } = recorder();
    const httpClient = new MockHttpClient();

    let hits;
    const handler = async (ctx: ConversationTurnContext) => {
      hits = await ctx.retrieve("kb-1", "query");
      await ctx.send(
        messageComplete({
          conversationId: ctx.conversationId,
          sequenceNo: ctx.assistantSequenceNo,
          content: "done",
        }),
      );
    };

    const d = new ConversationDispatcher({
      send,
      model: new StreamModel(),
      handler,
      httpClient: httpClient as any,
      agentAccessToken: "token",
    });

    d.handleTurnAssign(turnPayload());

    await vi.waitFor(() => expect(hits).toBeDefined());

    expect(hits![0]).toMatchObject({
      sourceName: "geography.txt",
      sourceId: "source-1",
      chunkId: "chunk-1",
      locator: "page 5",
    });
  });
});

describe("ConversationDispatcher native image parts (#103 Slice A)", () => {
  /** Captures the transcript the model is run with so we can assert the
   * user message was widened to multimodal parts. */
  class CapturingStreamModel implements ChatModel {
    lastMessages?: ConversationMessage[];
    async invoke(messages: ConversationMessage[]): Promise<ModelResponse> {
      this.lastMessages = messages;
      return { content: "ok" };
    }
    async *stream(messages: ConversationMessage[]): AsyncIterable<ModelStreamChunk> {
      this.lastMessages = messages;
      yield { content: "ok" };
    }
  }

  const imageAttachment = (overrides: Record<string, unknown> = {}) => ({
    id: "img-1",
    filename: "shot.png",
    mediaType: "image/png",
    sizeBytes: 4,
    image: true,
    readContentPath: "/api/v1/agent/conversations/c1/attachments/img-1/content",
    ...overrides,
  });

  async function runTurnCapturing(opts: {
    attachments: unknown[];
    maxImageBytes?: number;
    fetch?: (path: string) => Promise<Uint8Array>;
  }): Promise<ConversationMessage[]> {
    const { send, frames } = recorder();
    const model = new CapturingStreamModel();
    const httpClient: Pick<EngineHttpClient, "fetchAttachmentContent"> = {
      fetchAttachmentContent: async (_token: string, path: string) =>
        (opts.fetch ?? (async () => new Uint8Array([1, 2, 3])))(path),
    };
    const d = new ConversationDispatcher({
      send,
      model,
      httpClient: httpClient as unknown as EngineHttpClient,
      agentAccessToken: "tok",
      ...(opts.maxImageBytes !== undefined
        ? { maxImageBytes: opts.maxImageBytes }
        : {}),
    });
    d.handleTurnAssign(turnPayload({ attachments: opts.attachments }));
    await vi.waitFor(() =>
      expect(frames.map((f) => f.type)).toContain(MessageType.MESSAGE_COMPLETE),
    );
    return model.lastMessages!;
  }

  it("inlines a vision image as a base64 image_url part on the user message", async () => {
    const bytes = new Uint8Array([1, 2, 3, 4]);
    const messages = await runTurnCapturing({
      attachments: [imageAttachment()],
      fetch: async () => bytes,
    });

    const userMsg = messages[messages.length - 1];
    expect(Array.isArray(userMsg.content)).toBe(true);
    const parts = userMsg.content as MessageContentPart[];
    expect(parts[0]).toEqual({
      type: "text",
      text: expect.stringContaining("hello"),
    });
    const expectedUrl = `data:image/png;base64,${Buffer.from(bytes).toString("base64")}`;
    expect(parts[1]).toEqual({ type: "image_url", image_url: { url: expectedUrl } });
  });

  it("degrades an oversized image to a text note with no image part", async () => {
    const messages = await runTurnCapturing({
      maxImageBytes: 2,
      attachments: [imageAttachment()],
      fetch: async () => new Uint8Array([1, 2, 3, 4]),
    });

    const userMsg = messages[messages.length - 1];
    expect(typeof userMsg.content).toBe("string");
    expect(userMsg.content as string).toContain(
      "[image 'shot.png' too large to inline]",
    );
  });

  it("degrades a fetch failure to an unavailable text note", async () => {
    const messages = await runTurnCapturing({
      attachments: [imageAttachment()],
      fetch: async () => {
        throw new Error("boom");
      },
    });

    const userMsg = messages[messages.length - 1];
    expect(typeof userMsg.content).toBe("string");
    expect(userMsg.content as string).toContain("[image 'shot.png' unavailable]");
  });

  it("does not add an image part when the attachment is not vision-flagged", async () => {
    const messages = await runTurnCapturing({
      attachments: [
        imageAttachment({ image: false, readContentPath: null }),
      ],
      fetch: async () => new Uint8Array([9, 9, 9]),
    });

    const userMsg = messages[messages.length - 1];
    expect(typeof userMsg.content).toBe("string");
  });
});

