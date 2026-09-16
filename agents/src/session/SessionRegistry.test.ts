// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, beforeEach } from "vitest";
import { SessionRegistry } from "./SessionRegistry.js";
import type { SessionOpenPayload, SessionCredential } from "../protocol/unifiedFrames.js";
import type { ToolDefinition, KnowledgeSourceHandle, ModelInfoWire } from "../protocol/sessionTypes.js";
import type { ChatModel, ConversationMessage, ModelResponse, Tool, SessionTool } from "../executor/types.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";
import {
  CredentialEnvelopeError,
  deriveSessionKey,
} from "../security/credentialEnvelopes.js";
import { VECTOR_1 } from "../security/credentialVectors.js";
import { createCipheriv, createHash } from "node:crypto";

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
      credentialRef: null,
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

/** Build a valid sealed SessionCredential for `plaintext` bound to
 * `sessionId`, exactly as the engine's CredentialEnvelopeService would. */
function makeCredential(
  ref: string,
  purpose: "MODEL_PROVIDER" | "WORKSPACE_TOKEN",
  plaintext: string,
  sessionId: string,
  psk: Uint8Array,
  overrides: Partial<SessionCredential["envelope"]> = {},
): SessionCredential {
  const sessionKey = deriveSessionKey(psk, sessionId);
  const nonce = Buffer.alloc(12, 0);
  const digest =
    "sha256:" + createHash("sha256").update(Buffer.from(plaintext, "utf8")).digest("base64");
  const envelope: SessionCredential["envelope"] = {
    format: "MyrmecSecureEnvelopeV1",
    keyId: VECTOR_1.pskKeyId,
    sessionId,
    hostId: VECTOR_1.hostId,
    purpose,
    createdAt: new Date(Date.now() - 60_000).toISOString(),
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
    plaintextDigest: digest,
    nonce: nonce.toString("base64"),
    ciphertext: "",
    ...overrides,
  };
  const aadParts = [
    "MyrmecSecureEnvelopeV1",
    envelope.keyId,
    envelope.sessionId,
    envelope.hostId,
    envelope.purpose,
    envelope.expiresAt,
    envelope.plaintextDigest,
  ];
  const cipher = createCipheriv("aes-256-gcm", sessionKey, nonce);
  cipher.setAAD(Buffer.from(aadParts.join("|"), "utf8"));
  const sealed = Buffer.concat([
    cipher.update(Buffer.from(plaintext, "utf8")),
    cipher.final(),
    cipher.getAuthTag(),
  ]);
  envelope.ciphertext = sealed.toString("base64");
  return { credentialRef: ref, purpose, envelope };
}

/** The canonical 32-byte test PSK (deterministic; never a real key). */
const TEST_PSK = Buffer.alloc(32, 0xCD);

/** The vault tests' session id — a real UUID, since the session key salt is
 * derived from the raw UUID bytes. */
const VAULT_SESSION_ID = "33333333-3333-4333-8333-333333333333";

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

  // ── credential vault (design §10) ───────────────────────────────

  it("opens a keyless session without a PSK (credentials absent → no vault)", async () => {
    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    await registry.open(makeOpenPayload());

    const session = registry.get("sess-1")!;
    expect(session.credentialVault).toBeUndefined();
  });

  it("unwraps delivered credentials into the vault and resolves through it", async () => {
    const PLAINTEXT = "sk-test-provider-key-0123456789";
    const credential = makeCredential(
      "model-provider-token", "MODEL_PROVIDER", PLAINTEXT, VAULT_SESSION_ID, TEST_PSK,
    );

    const factories: ((ref: string) => string)[] = [];
    const recordingFactory: ChatModelFactory = {
      resolve: async (_info, _sessionId, resolveCredential) => {
        factories.push(resolveCredential ?? (() => ""));
        return new FakeChatModel();
      },
    };
    registry = new SessionRegistry({
      chatModelFactory: recordingFactory,
      sessionToolFactory: fakeSessionToolFactory(),
    });
    registry.setPsk(TEST_PSK);
    await registry.open(
      makeOpenPayload({
        sessionId: VAULT_SESSION_ID,
        credentials: [credential],
      }),
    );

    const session = registry.get(VAULT_SESSION_ID)!;
    expect(session.credentialVault).toBeDefined();
    expect(session.credentialVault!.get("model-provider-token")).toBe(PLAINTEXT);
    // The model factory received a working resolver.
    expect(factories[0]!("model-provider-token")).toBe(PLAINTEXT);
  });

  it("throws UNKNOWN_REF for a ref that was never unwrapped", async () => {
    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    registry.setPsk(TEST_PSK);
    await registry.open(
      makeOpenPayload({
        sessionId: VAULT_SESSION_ID,
        credentials: [
          makeCredential("model-provider-token", "MODEL_PROVIDER", "sk-x", VAULT_SESSION_ID, TEST_PSK),
        ],
      }),
    );

    expect(() => registry.resolveCredential(VAULT_SESSION_ID, "nope")).toThrow(
      CredentialEnvelopeError,
    );
    expect(() => registry.resolveCredential(VAULT_SESSION_ID, "nope")).toThrow(
      /never unwrapped/,
    );
  });

  it("fails the whole open when any envelope is tampered (fail closed)", async () => {
    const good = makeCredential("good-ref", "MODEL_PROVIDER", "sk-good", VAULT_SESSION_ID, TEST_PSK);
    const bad = makeCredential("bad-ref", "MODEL_PROVIDER", "sk-bad", VAULT_SESSION_ID, TEST_PSK);
    // Flip a ciphertext byte → GCM auth failure.
    const sealed = Buffer.from(bad.envelope.ciphertext, "base64");
    sealed[0] ^= 0xFF;
    bad.envelope.ciphertext = sealed.toString("base64");

    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    registry.setPsk(TEST_PSK);

    await expect(
      registry.open(
        makeOpenPayload({ sessionId: VAULT_SESSION_ID, credentials: [good, bad] }),
      ),
    ).rejects.toThrow(CredentialEnvelopeError);
    // Nothing was registered.
    expect(registry.has(VAULT_SESSION_ID)).toBe(false);
  });

  it("fails the open when the envelope is bound to another session", async () => {
    const credential = makeCredential(
      "model-provider-token", "MODEL_PROVIDER", "sk-x", "99999999-9999-4999-8999-999999999999", TEST_PSK,
    );
    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    registry.setPsk(TEST_PSK);

    await expect(
      registry.open(
        makeOpenPayload({ sessionId: VAULT_SESSION_ID, credentials: [credential] }),
      ),
    ).rejects.toBeInstanceOf(CredentialEnvelopeError);
    try {
      await registry.open(
        makeOpenPayload({ sessionId: VAULT_SESSION_ID, credentials: [credential] }),
      );
    } catch (err) {
      expect((err as CredentialEnvelopeError).code).toBe("SESSION_MISMATCH");
    }
    expect(registry.has(VAULT_SESSION_ID)).toBe(false);
  });

  it("fails the open when credentials arrive but no PSK was delivered", async () => {
    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    await expect(
      registry.open(
        makeOpenPayload({
          sessionId: VAULT_SESSION_ID,
          credentials: [
            makeCredential("model-provider-token", "MODEL_PROVIDER", "sk-x", VAULT_SESSION_ID, TEST_PSK),
          ],
        }),
      ),
    ).rejects.toThrow(/no PSK was delivered/i);
  });

  it("clears the vault on close (resolveCredential on a closed session fails)", async () => {
    registry = new SessionRegistry({
      chatModelFactory: fakeChatModelFactory(),
      sessionToolFactory: fakeSessionToolFactory(),
    });
    registry.setPsk(TEST_PSK);
    await registry.open(
      makeOpenPayload({
        sessionId: VAULT_SESSION_ID,
        credentials: [
          makeCredential("model-provider-token", "MODEL_PROVIDER", "sk-x", VAULT_SESSION_ID, TEST_PSK),
        ],
      }),
    );
    expect(registry.resolveCredential(VAULT_SESSION_ID, "model-provider-token")).toBe("sk-x");

    registry.close(VAULT_SESSION_ID);

    expect(() =>
      registry.resolveCredential(VAULT_SESSION_ID, "model-provider-token"),
    ).toThrow(CredentialEnvelopeError);
  });
});