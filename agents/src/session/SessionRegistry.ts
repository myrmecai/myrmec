// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Agent-side session state for the unified inference dispatch protocol (T10).
 *
 * The engine opens a session with `session.open`, carrying the authorized
 * model config, tool catalog, and knowledge-source handles. The agent keeps
 * one {@link Session} entry per `sessionId` for the life of the session and
 * tears it down on `session.close`. The model is resolved ONCE at open time
 * and reused across every turn in the session.
 */
import type { SessionOpenPayload, SessionCredential } from "../protocol/unifiedFrames.js";
import type { ModelInfoWire } from "../protocol/sessionTypes.js";
import type { ChatModel, SessionTool } from "../executor/types.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";
import type { Logger } from "../models/index.js";
import {
  CredentialEnvelopeError,
  deriveSessionKey,
  openEnvelope,
} from "../security/credentialEnvelopes.js";

/** Agent-side state for one open session. */
export interface Session {
  sessionId: string;
  kind: "WORKFLOW" | "CONVERSATION";
  /** §7.3 (§21.4): the dispatch context from session.open — "ORCHESTRATION"
   *  on orchestration sessions, null for conversations. */
  executionMode?: string | null;
  /** §7.3 (§21.4): the orchestration external reference, null when absent. */
  ref?: string | null;
  projectId: string;
  /** Resolved chat model — instantiated once at open, disposed at close. */
  model: ChatModel;
  /** Authorized tool implementations (with risk class), keyed by tool name. */
  tools: Map<string, SessionTool>;
  /** Knowledge-source ids this session may retrieve from (for ctx.retrieve). */
  knowledgeSourceIds: Set<string>;
  /** Project HITL policy: when true, DESTRUCTIVE/IRREVERSIBLE tools require
   *  human approval before execution. */
  autoHitlOnDestructive: boolean;  /** §16.2 (P6-T6): an ORCHESTRATOR session's complete self-contained
   *  assignment as delivered on session.open — the assignment IS the
   *  session's context. Null on ordinary sessions. */
  orchestration?: Record<string, unknown> | null;
  /** §16.3 sha-256 of the canonical assignment bytes. */
  assignmentDigest?: string | null;
  /**
   * Credential vault (design 2026-09-16-credential-envelope-delivery.md
   * §10): unwrapped `credentialRef → plaintext` for this session, populated
   * from session.open envelopes and cleared when the session closes. The
   * plaintext never enters any log line or serialized frame.
   */
  credentialVault?: Map<string, string>;
}

/** Constructor options for {@link SessionRegistry}. */
export interface SessionRegistryOptions {
  /** Factory that resolves a ChatModel for each session. */
  chatModelFactory: ChatModelFactory;
  /** Factory that resolves tool implementations for each session. */
  sessionToolFactory: SessionToolFactory;
  /** Logger; defaults to console. */
  logger?: Logger;
}

/**
 * Manages agent-side session state keyed by `sessionId`.
 *
 * On `session.open`:
 *   - resolves the {@link ChatModel} ONCE via the injected
 *     {@link ChatModelFactory} and stores it
 *   - builds the tool map via the injected {@link SessionToolFactory}
 *   - stores the knowledge-source ids for later `ctx.retrieve` calls
 *
 * On `session.close`:
 *   - disposes the model (when it carries a `close`/cleanup hook) and drops
 *     the entry
 */
export class SessionRegistry {
  private readonly sessions = new Map<string, Session>();
  private readonly chatModelFactory: ChatModelFactory;
  private readonly sessionToolFactory: SessionToolFactory;
  private readonly logger: Logger;
  /**
   * This run's PSK (design §6/§10): delivered on `host.opened`, held in
   * process memory only — never persisted, never logged, never stored on a
   * Session record. Destroyed with the process / on disconnect.
   */
  private psk: Uint8Array | null = null;

  constructor(options: SessionRegistryOptions) {
    this.chatModelFactory = options.chatModelFactory;
    this.sessionToolFactory = options.sessionToolFactory;
    this.logger = options.logger ?? console;
  }

  /**
   * Receive the run's PSK (design §6). Called by the transport layer right
   * after `host.opened`; kept in a private field ONLY — it never lands on a
   * Session record or any log line.
   */
  setPsk(pskBytes: Uint8Array): void {
    if (pskBytes.length !== 32) {
      throw new CredentialEnvelopeError("TAMPERED", "PSK must be exactly 32 bytes");
    }
    this.psk = pskBytes;
  }

  /**
   * Open a session: resolve the model, bind tools, store knowledge sources,
   * unwrap delivered credential envelopes into the session vault.
   * Throws if the model cannot be resolved, a catalogued tool has no
   * matching implementation, or any credential envelope fails to open —
   * a failed open tears the session down (fail closed).
   */
  async open(payload: SessionOpenPayload): Promise<void> {
    // Credential vault (design §10): unwrap every envelope BEFORE the model
    // is resolved so the factory resolver can reach the vault. Any failure
    // aborts the open — nothing half-decrypted lingers.
    const credentialVault = payload.credentials?.length
      ? await this.buildVault(payload.sessionId, payload.credentials)
      : undefined;
    try {
      const modelInfo = payload.model as unknown as ModelInfoWire;
      const model = await this.chatModelFactory.resolve(
        modelInfo,
        payload.sessionId,
        credentialVault ? (ref) => this.resolveFromVault(credentialVault, ref) : undefined,
      );
      // zod's z.infer widens riskClass to string — re-narrow to the literal
      // union the session-tool factory expects.
      const tools = await this.sessionToolFactory.resolve(
        payload.tools.map((t) => ({
          ...t,
          riskClass: (t.riskClass as "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE") ?? "SAFE",
        })),
      );

      const knowledgeSourceIds = new Set(
        payload.knowledgeSources.map((ks) => ks.id),
      );

      const session: Session = {
        sessionId: payload.sessionId,
        kind: payload.kind === "WORKFLOW" ? "WORKFLOW" : "CONVERSATION",
        executionMode: payload.executionMode ?? null,
        ref: payload.ref ?? null,
        projectId: payload.projectId,
        model,
        tools,
        knowledgeSourceIds,
        autoHitlOnDestructive: payload.autoHitlOnDestructive ?? false,
        // §16.2/§16.3: pass the orchestration assignment through — the
        // orchestration executor reads it from the session, not from a
        // legacy inference.assign wire.
        orchestration: payload.orchestration ?? null,
        assignmentDigest: payload.assignmentDigest ?? null,
        ...(credentialVault ? { credentialVault } : {}),
      };
      this.sessions.set(payload.sessionId, session);

      this.logger.info(
        `Session opened: ${payload.sessionId} (${payload.kind}) — ${tools.size} tools, ${knowledgeSourceIds.size} knowledge sources${
          payload.orchestration ? ", orchestration assignment installed" : ""
        }${credentialVault ? `, ${credentialVault.size} credentials unwrapped` : ""}`,
      );
    } catch (err) {
      // A failed open must not leak already-unwrapped plaintexts (§10).
      credentialVault?.clear();
      throw err;
    }
  }

  /**
   * Unwrap every session credential envelope into the vault (design §10).
   * Requires the run PSK; any envelope failure (tamper/expiry/session
   * binding/duplicate ref) fails the whole open.
   */
  private async buildVault(
    sessionId: string,
    credentials: SessionCredential[],
  ): Promise<Map<string, string>> {
    if (!this.psk) {
      throw new CredentialEnvelopeError(
        "MISSING_PSK",
        "session.open carried credentials but no PSK was delivered on host.opened",
      );
    }
    const sessionKey = deriveSessionKey(this.psk, sessionId);
    const vault = new Map<string, string>();
    for (const credential of credentials) {
      if (vault.has(credential.credentialRef)) {
        // Protocol §7.3 rule 1: a ref resolves exactly once per session.
        vault.clear();
        throw new CredentialEnvelopeError(
          "DUPLICATE_REF",
          `credentialRef '${credential.credentialRef}' delivered more than once`,
        );
      }
      if (credential.envelope.sessionId !== sessionId) {
        // Cross-session replay — validate explicitly so the failure code is
        // precise even when the GCM binding would also catch it.
        vault.clear();
        throw new CredentialEnvelopeError(
          "SESSION_MISMATCH",
          `credential '${credential.credentialRef}' is bound to session '${credential.envelope.sessionId}', expected '${sessionId}'`,
        );
      }
      try {
        vault.set(
          credential.credentialRef,
          openEnvelope(credential.envelope, sessionKey, sessionId),
        );
      } catch (err) {
        vault.clear();
        throw err;
      }
    }
    return vault;
  }

  /** Vault-backed plaintext lookup; unknown refs fail closed. */
  private resolveFromVault(vault: Map<string, string>, ref: string): string {
    const value = vault.get(ref);
    if (value === undefined) {
      throw new CredentialEnvelopeError("UNKNOWN_REF", `credentialRef '${ref}' was never unwrapped`);
    }
    return value;
  }

  /**
   * Resolver handed to consumers (tool/workspace factories as they grow
   * credential needs): returns the plaintext for a ref opened in this
   * session, or throws `UNKNOWN_REF` (design §10).
   */
  resolveCredential(sessionId: string, ref: string): string {
    const session = this.sessions.get(sessionId);
    const vault = session?.credentialVault;
    if (!vault) {
      throw new CredentialEnvelopeError(
        "UNKNOWN_REF",
        `no credential vault for session '${sessionId}' (credentialRef '${ref}')`,
      );
    }
    return this.resolveFromVault(vault, ref);
  }

  /**
   * Close a session: dispose the model (best-effort), clear the credential
   * vault (§10: the vault dies with the session entry — no plaintext may
   * outlive its session) and drop the entry. No-op if already gone.
   */
  close(sessionId: string): void {
    const session = this.sessions.get(sessionId);
    if (!session) {
      return;
    }
    // Best-effort cleanup; ChatModel has no required dispose hook today.
    const maybeDisposable = session.model as unknown as {
      close?: () => void;
    };
    maybeDisposable.close?.();
    session.credentialVault?.clear();
    this.sessions.delete(sessionId);
    this.logger.debug(`Session closed: ${sessionId}`);
  }

  /** Get the session for a sessionId, or undefined if not open. */
  get(sessionId: string): Session | undefined {
    return this.sessions.get(sessionId);
  }

  /** Whether a session is currently open for this sessionId. */
  has(sessionId: string): boolean {
    return this.sessions.has(sessionId);
  }
}