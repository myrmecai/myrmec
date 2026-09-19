// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ConversationEventReporter (§8.4): the conversation event-stream producer.
 *
 * <p>Implements the {@link ExecutorEvents} seam the {@link TurnExecutor}
 * loop fires (progress / tool start / tool end) and turns each callback into
 * a schema-valid `execution.event` frame on the {@link ExecutionFrameSender}
 * — the sender plumbing already routes it worker → supervisor → engine
 * (HostControlClient.sendExecutionEvent).</p>
 *
 * <p>§15 rule 12: EVERY data map passes through the session's
 * {@link CaptureFilter} before it is sent — tool arguments, tool results,
 * prompts, and provider payloads never leave the SDK unless the capture
 * policy is FULL (and even then the maxBytes budget truncates).</p>
 */
import { randomUUID } from "node:crypto";
import type { Logger } from "../models/index.js";
import type { ToolCallRecord } from "../models/index.js";
import type {
  ExecutionEventPayload,
  CapturePolicy,
} from "../protocol/unifiedFrames.js";
import type { ExecutorEvents } from "./types.js";
import type { ExecutionFrameSender } from "./ExecutionFrameSender.js";
import { CaptureFilter } from "./CaptureFilter.js";

/** Constructor options for {@link ConversationEventReporter}. */
export interface ConversationEventReporterOptions {
  /** The unified execution-frame sender (worker's outbound surface). */
  sender: ExecutionFrameSender;
  /** The session's capture filter (built from the registry capture policy). */
  filter: CaptureFilter;
  /** Logger; defaults to console. */
  logger?: Logger;
  /** Override the eventId generator (tests). Defaults to a UUID. */
  generateEventId?: () => string;
}

/**
 * The producer. One instance serves one session; the reporter is bound to
 * an execution at turn start via {@link beginExecution} (the per-turn
 * executionId — the same pattern the executor's in-flight map uses, keyed
 * per execution rather than per constructor, so concurrent turns of
 * different executions never cross-write frames).
 */
export class ConversationEventReporter implements ExecutorEvents {
  private readonly sender: ExecutionFrameSender;
  private filter: CaptureFilter;
  private readonly log: Logger;
  private readonly generateEventId: () => string;
  /** The execution this reporter currently serves (set per turn). */
  private executionId: string | null = null;

  constructor(options: ConversationEventReporterOptions) {
    if (!options.sender) {
      throw new Error("ConversationEventReporter requires a sender");
    }
    if (!options.filter) {
      throw new Error("ConversationEventReporter requires a capture filter");
    }
    this.sender = options.sender;
    this.filter = options.filter;
    this.log = options.logger ?? console;
    this.generateEventId = options.generateEventId ?? randomUUID;
  }

  /**
   * Bind the reporter to the execution it serves. Called by the executor at
   * turn start (after the executionId is known); cleared at turn end so a
   * stale execution id can never ride a later callback.
   */
  beginExecution(executionId: string): void {
    this.executionId = executionId;
  }

  /** Clear the per-turn binding (terminal housekeeping). */
  endExecution(): void {
    this.executionId = null;
  }

  /**
   * §7.3/§15 rule 12: re-bind the capture policy when `session.open`
   * delivers it. The constructor default is METADATA (fail closed) because
   * the reporter outlives sessions; the real session policy replaces it
   * per open.
   */
  bindCapturePolicy(capture: CapturePolicy | null): void {
    this.filter = new CaptureFilter(capture ?? null, this.log);
  }

  /** Coarse loop progress (§8.4 PROGRESS: bounded message + percentage). */
  async onProgress(progress: number, iteration: number): Promise<void> {
    await this.emit("PROGRESS", {
      message: `iteration ${iteration}`,
      percentage: progress,
    });
  }

  /**
   * Tool start (§8.4 TOOL_STARTED: tool name + call ID). The record's
   * `args` ride the frame ONLY when the capture policy is FULL — at
   * METADATA the filter strips them before the frame is sent.
   */
  async onToolStart(record: ToolCallRecord): Promise<void> {
    await this.emit("TOOL_STARTED", {
      toolName: record.toolName,
      callId: record.toolCallId,
      args: record.args,
    });
  }

  /**
   * Tool settle (§8.4 TOOL_COMPLETED: tool name, call ID, duration, error
   * flag). The `result`/`error` content rides the frame ONLY when the
   * capture policy is FULL — at METADATA the filter strips them, leaving
   * the boolean error flag.
   */
  async onToolEnd(record: ToolCallRecord): Promise<void> {
    const durationMs =
      record.completedAt !== undefined ? Math.max(0, record.completedAt - record.startedAt) : 0;
    await this.emit("TOOL_COMPLETED", {
      toolName: record.toolName,
      callId: record.toolCallId,
      durationMs,
      isError: record.error !== undefined,
      args: record.args,
      ...(record.result !== undefined ? { result: record.result } : {}),
      ...(record.error !== undefined ? { error: record.error } : {}),
    });
  }

  /** Build + filter + send one execution.event frame. */
  private async emit(
    eventType: string,
    data: Record<string, unknown>,
  ): Promise<void> {
    const executionId = this.executionId;
    if (!executionId) {
      // Not bound to an execution — nothing to correlate the frame with.
      this.log.warn(
        `ConversationEventReporter: ${eventType} dropped (no execution bound)`,
      );
      return;
    }
    const filtered = this.filter.truncate(
      this.filter.filterEvent(eventType, data),
      eventType,
    );
    const payload: ExecutionEventPayload = {
      executionId,
      eventId: this.generateEventId(),
      eventType,
      occurredAt: new Date().toISOString(),
      data: filtered,
    };
    await this.sender.sendExecutionEvent(payload);
  }
}