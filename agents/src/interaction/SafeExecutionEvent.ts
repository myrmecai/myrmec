// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * SafeExecutionEvent (design 14.2/14.6, protocol 22.6): the immutable,
 * bounded metadata event the interaction tools surface. Built ONLY from
 * the execution's safe observable facts - event type, UTC instant, and
 * the already-sanitized metadata columns the CaptureFilter allowlist
 * passes. No prompts, no tool arguments/results, no file contents, no
 * credentials, no raw provider fragments (14.6: the interaction surfaces
 * immutable snapshots and safe event summaries only).
 */

/** One bounded safe event observation. `data` carries the sanitized
 * metadata block exactly as it crossed the public event stream. */
export interface SafeExecutionEvent {
  /** The protocol event type (e.g. PROGRESS, CHECKPOINT_CREATED). */
  type: string;
  /** UTC ISO instant the event was observed. */
  at: string;
  /** The sanitized metadata columns (bounded; already filtered). */
  data: Readonly<Record<string, unknown>>;
}

/** Maximum events the interaction tools may surface in one call. */
export const MAX_RECENT_EVENTS = 50;

/**
 * A bounded ring of recent safe events (insertion order, oldest first).
 * The interaction controller records sanitized events as the execution
 * progresses; get_recent_events returns the newest `limit` entries.
 */
export class SafeExecutionEventRing {
  private readonly events: SafeExecutionEvent[] = [];

  constructor(private readonly capacity: number = MAX_RECENT_EVENTS) {}

  /** Record one event; drops the oldest beyond the capacity. */
  record(event: SafeExecutionEvent): void {
    this.events.push(event);
    while (this.events.length > this.capacity) {
      this.events.shift();
    }
  }

  /** The newest `limit` events, oldest first. `limit` is clamped to the
   * ring capacity (an absurd limit never widens the surface). */
  recent(limit: number): ReadonlyArray<SafeExecutionEvent> {
    const bounded = Math.max(
      0,
      Math.min(Math.floor(limit), this.capacity, this.events.length),
    );
    return Object.freeze(this.events.slice(this.events.length - bounded));
  }

  /** Current event count (observability/tests). */
  get size(): number {
    return this.events.length;
  }
}