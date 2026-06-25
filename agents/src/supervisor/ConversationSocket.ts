// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * One dedicated conversation socket owned by the Supervisor (agent-concurrency
 * §9.9). Opened when the engine binds this agent to a conversation: it dials
 * the home engine node, sends `conversation.attach`, then carries the turn
 * loop — inbound `conversation.turn.assign` / `approval.decision` flow to the
 * worker, and the worker's `message.delta` / `message.complete` /
 * `approval.request` frames flow back out over this same socket.
 *
 * The Supervisor owns the socket; the worker stays byte-identical and never
 * sees which connection its frames ride (§9.3 seam 4). The connection is
 * injected via {@link ConversationConnectionLike} so a `WebSocketConnection`
 * is used in production and a fake can stand in for tests.
 */
import type { Envelope } from "../protocol/envelope.js";
import { conversationAttach } from "../protocol/conversationFrames.js";
import type { Logger } from "../models/index.js";

/** The minimal connection surface a conversation socket needs. A
 * `WebSocketConnection` satisfies it; a fake stands in for tests. */
export interface ConversationConnectionLike {
  connect(accessToken: string): Promise<void>;
  send(frame: Envelope): Promise<void>;
  disconnect(reason?: string): Promise<void>;
  readonly isConnected: boolean;
}

export interface ConversationSocketOptions {
  agentId: string;
  conversationId: string;
  connection: ConversationConnectionLike;
  logger?: Logger;
}

export class ConversationSocket {
  readonly conversationId: string;
  private readonly agentId: string;
  private readonly connection: ConversationConnectionLike;
  private readonly log: Logger;

  constructor(options: ConversationSocketOptions) {
    this.agentId = options.agentId;
    this.conversationId = options.conversationId;
    this.connection = options.connection;
    this.log = options.logger ?? console;
  }

  /** Dial the home node, then send `conversation.attach` so the engine pins
   * the conversation home node and flips this agent to BOUND. */
  async open(accessToken: string): Promise<void> {
    await this.connection.connect(accessToken);
    await this.connection.send(
      conversationAttach({
        agentId: this.agentId,
        conversationId: this.conversationId,
      }),
    );
    this.log.debug("Conversation socket attached", this.conversationId);
  }

  /** Send a worker-produced frame over the conversation socket. */
  send(frame: Envelope): Promise<void> {
    return this.connection.send(frame);
  }

  get isConnected(): boolean {
    return this.connection.isConnected;
  }

  /** Tear the socket down (on `agent.release` / home-node loss). */
  async close(reason = "conversation released"): Promise<void> {
    await this.connection.disconnect(reason);
  }
}
