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
import type { SessionOpenPayload } from "../protocol/inferenceFrames.js";
import type { ModelInfoWire } from "../protocol/taskFrames.js";
import type { ChatModel, SessionTool } from "../executor/types.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";
import type { Logger } from "../models/index.js";

/** Agent-side state for one open session. */
export interface Session {
  sessionId: string;
  serviceType: "WORKFLOW" | "CONVERSATION";
  projectId: string;
  /** Resolved chat model — instantiated once at open, disposed at close. */
  model: ChatModel;
  /** Authorized tool implementations (with risk class), keyed by tool name. */
  tools: Map<string, SessionTool>;
  /** Knowledge-source ids this session may retrieve from (for ctx.retrieve). */
  knowledgeSourceIds: Set<string>;
  /** Project HITL policy: when true, DESTRUCTIVE/IRREVERSIBLE tools require
   *  human approval before execution. */
  autoHitlOnDestructive: boolean;
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

  constructor(options: SessionRegistryOptions) {
    this.chatModelFactory = options.chatModelFactory;
    this.sessionToolFactory = options.sessionToolFactory;
    this.logger = options.logger ?? console;
  }

  /**
   * Open a session: resolve the model, bind tools, store knowledge sources.
   * Throws if the model cannot be resolved or a catalogued tool has no
   * matching implementation.
   */
  async open(payload: SessionOpenPayload): Promise<void> {
    const modelInfo = payload.model as unknown as ModelInfoWire;
    const model = await this.chatModelFactory.resolve(modelInfo, payload.sessionId);
    const tools = await this.sessionToolFactory.resolve(payload.tools);

    const knowledgeSourceIds = new Set(
      payload.knowledgeSources.map((ks) => ks.knowledgeSourceId),
    );

    const session: Session = {
      sessionId: payload.sessionId,
      serviceType: payload.serviceType,
      projectId: payload.projectId,
      model,
      tools,
      knowledgeSourceIds,
      autoHitlOnDestructive: payload.autoHitlOnDestructive ?? false,
    };
    this.sessions.set(payload.sessionId, session);

    this.logger.info(
      `Session opened: ${payload.sessionId} (${payload.serviceType}) — ${tools.size} tools, ${knowledgeSourceIds.size} knowledge sources`,
    );
  }

  /**
   * Close a session: dispose the model (best-effort) and drop the entry.
   * No-op if the session is already gone.
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