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
import type {
  SessionOpenPayload,
  SessionCredential,
  ChannelOpenedPayload,
  SessionPolicy,
  CapturePolicy,
} from "../protocol/unifiedFrames.js";
import type { ModelInfoWire } from "../protocol/sessionTypes.js";
import type { ChatModel, SessionTool } from "../executor/types.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";
import type { Logger } from "../models/index.js";
import { PolicyEnforcer } from "../executor/PolicyEnforcer.js";
import {
  CredentialEnvelopeError,
  deriveSessionKey,
  openEnvelope,
} from "../security/credentialEnvelopes.js";

/** §7.5 (A1): the bound dedicated-channel transport for one session. The
 * client owns the socket + handshake; the registry only records the state so
 * close() can tear the socket down alongside the session. */
export interface SessionChannelState {
  sessionId: string;
  /** The opened channel socket abstraction (never null while alive). */
  socket: unknown;
  /** False once the channel was lost (close/error) — non-fatal; the session
   * keeps riding the control socket. */
  alive: boolean;
  /** The engine-reported cursor at bind time (§12.3 replay resume point). */
  highestContiguousSequence: number;
}

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
  /**
   * §7.5 (A1): the bound dedicated channel for this session, set after the
   * channel handshake completes. Null when the session rides the control
   * socket only (channel offer absent, or channel lost — non-fatal).
   */
  channel?: SessionChannelState | null;
  /**
   * §7.5 (A1): the host's durable-event cursor for this session — the
   * highest contiguous sequence the host has durably recorded (protocol.ack
   * reply sequence). Feeds `channel.open.resumeFromSequence` (§12.3).
   * Undefined reads as 0.
   */
  highestContiguousSequence?: number;
  /**
   * §13 (A2): true while the control socket is down inside the retention
   * window — the entry SURVIVES the drop (cursors, vaults, models stay)
   * pending host.resume/host.reconcile; cleared on KEEP re-bind.
   */
  disconnected?: boolean;
  /**
   * §7.3 (Wave 6, A4): the host-enforced execution limits shipped on
   * session.open. Null/absent fields mean "no host-side limit".
   */
  policy?: SessionPolicy | null;
  /**
   * §7.3/§15 rule 12 (Wave 6): the sensitive-capture policy — METADATA is
   * the V1 default (no sensitive payloads on the event stream).
   */
  capture?: CapturePolicy | null;
}

/** §13 (A2): one host.resume retained-session summary built from registry state. */
export interface RetainedSessionSummary {
  sessionId: string;
  /** §13 retained-state string — "ACTIVE" for every session the host holds. */
  state: string;
  /**
   * The host holds one local slot per retained session. V1 has no eviction
   * on drop, so every registry entry asserts a held slot (§13: the engine
   * may KEEP only when this is true).
   */
  capacityHeld: boolean;
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
        channel: null,
        highestContiguousSequence: 0,
        ...(credentialVault ? { credentialVault } : {}),
        // §7.3 (Wave 6, A4): the session's enforced policy + capture limits.
        policy: payload.policy ?? null,
        capture: payload.capture ?? null,
      };
      this.sessions.set(payload.sessionId, session);

      this.logger.info(
        `Session opened: ${payload.sessionId} (${payload.kind}) — ${tools.size} tools, ${knowledgeSourceIds.size} knowledge sources${
          payload.orchestration ? ", orchestration assignment installed" : ""
        }${credentialVault ? `, ${credentialVault.size} credentials unwrapped` : ""}${
          payload.policy ? ", policy enforced" : ""
        }`,
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

  // ==================== Dedicated channel state (§7.5, A1) ====================

  /**
   * Record a bound channel socket for the session after the channel handshake
   * completes. The socket reference is opaque to the registry — the client
   * owns connect/close/dispatch; the registry only tracks liveness so
   * session close can tear the socket down.
   */
  bindChannel(
    sessionId: string,
    socket: unknown,
    opened: ChannelOpenedPayload,
  ): void {
    const session = this.sessions.get(sessionId);
    if (!session) {
      return;
    }
    session.channel = {
      sessionId,
      socket,
      alive: true,
      highestContiguousSequence: opened.highestContiguousSequence,
    };
  }

  /**
   * Mark the channel dead for a session (§7.5: channel loss is NON-fatal —
   * the session stays open on the control socket). No-op if the session is
   * gone or already dead.
   */
  markChannelDead(sessionId: string): void {
    const session = this.sessions.get(sessionId);
    if (!session?.channel?.alive) {
      return;
    }
    session.channel.alive = false;
  }

  /**
   * Record a durable event's sequence for the session — the registry's
   * highest-contiguous-sequence tracking feeds both `protocol.ack` replies
   * and `channel.open.resumeFromSequence`. Monotonic; replays are ignored.
   */
  observeDurableSequence(sessionId: string, sequence: number): void {
    const session = this.sessions.get(sessionId);
    if (!session || sequence <= (session.highestContiguousSequence ?? 0)) {
      return;
    }
    session.highestContiguousSequence = sequence;
  }

  /** Read the session's durable-event cursor (0 when unknown). */
  getHighestContiguousSequence(sessionId: string): number {
    return this.sessions.get(sessionId)?.highestContiguousSequence ?? 0;
  }

  /**
   * Drop the channel binding on session close: the client receives the
   * socket back via the returned state so it can close the transport.
   */
  unbindChannel(sessionId: string): SessionChannelState | null {
    const session = this.sessions.get(sessionId);
    const channel = session?.channel ?? null;
    if (session) {
      session.channel = null;
    }
    return channel;
  }

  // ==================== §13 retention (A2) ====================

  /**
   * Mark every session as retained across a control-socket drop (§13).
   * Entries, cursors, channels (as dead) and credential vaults all SURVIVE
   * — teardown happens only on a CLOSE decision or retention expiry.
   */
  markAllDisconnected(): void {
    for (const session of this.sessions.values()) {
      session.disconnected = true;
      if (session.channel) {
        session.channel.alive = false;
      }
    }
    this.logger.debug(
      `Retention armed: ${this.sessions.size} session(s) survive the drop`,
    );
  }

  /** Whether the session was retained across the drop and still is. */
  isRetained(sessionId: string): boolean {
    return this.sessions.get(sessionId)?.disconnected ?? false;
  }

  /** All retained (disconnected) session ids, insertion order. */
  retainedSessionIds(): string[] {
    return [...this.sessions.entries()]
      .filter(([, s]) => s.disconnected)
      .map(([id]) => id);
  }

  /** Build the §13 host.resume summaries for every retained session. */
  buildRetainedSummaries(): RetainedSessionSummary[] {
    return this.retainedSessionIds().map((sessionId) => ({
      sessionId,
      state: "ACTIVE",
      capacityHeld: true,
    }));
  }

  /**
   * Re-bind a session the engine decided to KEEP (§13): clears the
   * disconnected marker; cursors, vaults and the model survive. The channel
   * binding stays dead — a fresh channel.open handshake (client side) binds
   * a new socket on the resumed connection. Returns the entry's durable
   * cursor (0 when unknown).
   */
  rebindAfterReconcile(sessionId: string): number {
    const session = this.sessions.get(sessionId);
    if (!session) {
      return 0;
    }
    session.disconnected = false;
    session.channel = null;
    return session.highestContiguousSequence ?? 0;
  }

  /**
   * Drop a session per a CLOSE/unknown reconcile decision (§13): the vault
   * dies with the entry (§10: no plaintext outlives its session), the model
   * is disposed best-effort. No-op if the session is already gone.
   */
  closeRetained(sessionId: string): void {
    this.close(sessionId);
  }

  // ==================== §8.7 policy enforcement (A4) ====================

  /**
   * One enforcer per in-flight execution (§8.7: per-execution monotonic
   * accounting). Created lazily from the session's policy; dropped with the
   * session.
   */
  private readonly enforcers = new Map<string, PolicyEnforcer>();

  /**
   * The (lazily created) §8.7 enforcer for an execution of a session. The
   * session's policy block seeds the iteration ceiling; a session with no
   * policy enforces nothing until an execution.policy.update arrives.
   * Unknown sessions yield undefined (fail-open to the executor's own
   * SESSION_NOT_OPEN path).
   *
   * Resolves by execution id when the executor registered one, else by
   * session id (a conversation session serves one execution at a time,
   * §11.3.4 — the session-keyed enforcer IS the execution's).
   */
  enforcerFor(sessionOrExecutionId: string): PolicyEnforcer | undefined {
    const session = this.sessions.get(sessionOrExecutionId);
    if (session) {
      return this.enforcerForSession(session.sessionId);
    }
    // An execution id the executor registered shares the session's enforcer;
    // an unregistered one gets a standalone enforcer (§8.7: the allowance
    // rides the updates themselves — no session policy is consulted).
    let enforcer = this.enforcers.get(sessionOrExecutionId);
    if (!enforcer) {
      enforcer = new PolicyEnforcer({ policy: null, logger: this.logger });
      this.enforcers.set(sessionOrExecutionId, enforcer);
    }
    return enforcer;
  }

  /** Lazily create (or return) the session-keyed enforcer. */
  private enforcerForSession(sessionId: string): PolicyEnforcer {
    let enforcer = this.enforcers.get(sessionId);
    if (!enforcer) {
      const session = this.sessions.get(sessionId);
      enforcer = new PolicyEnforcer({
        policy: session?.policy ?? null,
        logger: this.logger,
      });
      this.enforcers.set(sessionId, enforcer);
    }
    return enforcer;
  }

  /**
   * §8.7 (A4): the execution start registers its enforcement state against
   * the executionId so policy updates addressed to the execution (not the
   * session) find it. Shares the session's enforcer (§11.3.4: one in-flight
   * execution per conversation session) so accounting stays monotonic
   * across turns of the same session.
   */
  registerExecution(sessionId: string, executionId: string): void {
    const enforcer = this.enforcerForSession(sessionId);
    if (enforcer) {
      this.enforcers.set(executionId, enforcer);
    }
  }

  /** Drop the execution's enforcer state (terminal housekeeping). */
  dropEnforcer(sessionOrExecutionId: string): void {
    this.enforcers.delete(sessionOrExecutionId);
  }
}