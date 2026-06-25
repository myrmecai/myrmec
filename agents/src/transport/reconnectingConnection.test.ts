// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect, vi } from "vitest";
import { ReconnectingConnection } from "./reconnectingConnection.js";
import type { WebSocketConnection } from "./connection.js";
import { CloseCode } from "../protocol/messages.js";

/**
 * Builds a ReconnectingConnection with fully fake collaborators so the
 * reconnect/backoff contract can be exercised without real sockets or timers.
 */
function makeSubject(
  overrides: {
    connect?: (token: string) => Promise<void>;
    getAccessToken?: () => Promise<string>;
    refreshToken?: () => Promise<string>;
    register?: () => Promise<string>;
    maxBackoffMs?: number;
    initialBackoffMs?: number;
  } = {},
) {
  const sleeps: number[] = [];
  const connect = vi.fn(overrides.connect ?? (async () => {}));
  const getAccessToken = vi.fn(
    overrides.getAccessToken ?? (async () => "access-token"),
  );
  const refreshToken = vi.fn(
    overrides.refreshToken ?? (async () => "refreshed-token"),
  );
  const register = vi.fn(overrides.register ?? (async () => "registered-token"));

  const connection = { connect } as unknown as WebSocketConnection;

  const subject = new ReconnectingConnection({
    connection,
    getAccessToken,
    refreshToken,
    register,
    maxBackoffMs: overrides.maxBackoffMs,
    initialBackoffMs: overrides.initialBackoffMs,
    sleep: async (ms: number) => {
      sleeps.push(ms);
    },
  });

  return { subject, sleeps, connect, getAccessToken, refreshToken, register };
}

describe("ReconnectingConnection.connectWithRetry — backoff", () => {
  it("connects on the first attempt without sleeping", async () => {
    const { subject, sleeps, connect, getAccessToken } = makeSubject();

    await subject.connectWithRetry();

    expect(connect).toHaveBeenCalledTimes(1);
    expect(connect).toHaveBeenCalledWith("access-token");
    expect(getAccessToken).toHaveBeenCalledTimes(1);
    expect(sleeps).toEqual([]);
  });

  it("retries with exponential backoff until a connect succeeds", async () => {
    let attempts = 0;
    const { subject, sleeps, connect } = makeSubject({
      connect: async () => {
        attempts += 1;
        if (attempts < 4) {
          throw new Error("connect failed");
        }
      },
    });

    await subject.connectWithRetry();

    // 3 failures → sleeps of 1s, 2s, 4s; 4th attempt succeeds.
    expect(connect).toHaveBeenCalledTimes(4);
    expect(sleeps).toEqual([1_000, 2_000, 4_000]);
  });

  it("caps the backoff at maxBackoffMs", async () => {
    let attempts = 0;
    const { subject, sleeps } = makeSubject({
      initialBackoffMs: 1_000,
      maxBackoffMs: 2_000,
      connect: async () => {
        attempts += 1;
        if (attempts < 5) {
          throw new Error("connect failed");
        }
      },
    });

    await subject.connectWithRetry();

    // 1s, 2s, then capped at 2s for the remaining failures.
    expect(sleeps).toEqual([1_000, 2_000, 2_000, 2_000]);
  });

  it("resets the backoff to the initial value after a success", async () => {
    let phase1Attempts = 0;
    let failNext = true;
    const { subject, sleeps } = makeSubject({
      connect: async () => {
        if (failNext) {
          phase1Attempts += 1;
          if (phase1Attempts < 3) {
            throw new Error("connect failed");
          }
        }
      },
    });

    await subject.connectWithRetry(); // fails twice (1s, 2s) then succeeds
    expect(sleeps).toEqual([1_000, 2_000]);

    // Next reconnect cycle: a single failure should restart at 1s, not 4s.
    failNext = false;
    let secondCycleAttempts = 0;
    const { subject: subject2, sleeps: sleeps2 } = makeSubject({
      connect: async () => {
        secondCycleAttempts += 1;
        if (secondCycleAttempts < 2) {
          throw new Error("connect failed");
        }
      },
    });
    await subject2.connectWithRetry();
    expect(sleeps2).toEqual([1_000]);
  });

  it("stops the retry loop when stop() is called mid-backoff", async () => {
    let subjectRef: ReconnectingConnection | null = null;
    const sleeps: number[] = [];
    const connect = vi.fn(async () => {
      throw new Error("connect failed");
    });
    const connection = { connect } as unknown as WebSocketConnection;

    subjectRef = new ReconnectingConnection({
      connection,
      getAccessToken: async () => "access-token",
      refreshToken: async () => "refreshed-token",
      register: async () => "registered-token",
      sleep: async (ms: number) => {
        sleeps.push(ms);
        subjectRef!.stop();
      },
    });

    await subjectRef.connectWithRetry();

    // One failed attempt, one sleep, then stop() breaks the loop.
    expect(connect).toHaveBeenCalledTimes(1);
    expect(sleeps).toEqual([1_000]);
  });

  it("does not attempt to connect after stop()", async () => {
    const { subject, getAccessToken, connect } = makeSubject();

    subject.stop();
    await subject.connectWithRetry();

    expect(getAccessToken).not.toHaveBeenCalled();
    expect(connect).not.toHaveBeenCalled();
  });
});

describe("ReconnectingConnection.handleDisconnect — close-code matrix", () => {
  it("NORMAL (1000): does not reconnect, no refresh/register", async () => {
    const { subject, refreshToken, register } = makeSubject();

    const reconnect = await subject.handleDisconnect(CloseCode.NORMAL, "bye");

    expect(reconnect).toBe(false);
    expect(refreshToken).not.toHaveBeenCalled();
    expect(register).not.toHaveBeenCalled();
  });

  it("TOKEN_EXPIRED (4001): refreshes the token and reconnects", async () => {
    const { subject, refreshToken, register } = makeSubject();

    const reconnect = await subject.handleDisconnect(
      CloseCode.TOKEN_EXPIRED,
      "expired",
    );

    expect(reconnect).toBe(true);
    expect(refreshToken).toHaveBeenCalledTimes(1);
    expect(register).not.toHaveBeenCalled();
  });

  it("TOKEN_EXPIRED (4001): falls back to re-register when refresh fails", async () => {
    const { subject, refreshToken, register } = makeSubject({
      refreshToken: async () => {
        throw new Error("refresh rejected");
      },
    });

    const reconnect = await subject.handleDisconnect(
      CloseCode.TOKEN_EXPIRED,
      "expired",
    );

    expect(reconnect).toBe(true);
    expect(refreshToken).toHaveBeenCalledTimes(1);
    expect(register).toHaveBeenCalledTimes(1);
  });

  it("INVALID_TOKEN (4002): re-registers and reconnects", async () => {
    const { subject, refreshToken, register } = makeSubject();

    const reconnect = await subject.handleDisconnect(
      CloseCode.INVALID_TOKEN,
      "invalid",
    );

    expect(reconnect).toBe(true);
    expect(register).toHaveBeenCalledTimes(1);
    expect(refreshToken).not.toHaveBeenCalled();
  });

  it("AGENT_DEACTIVATED (4003): stops permanently", async () => {
    const { subject, getAccessToken, refreshToken, register } = makeSubject();

    const reconnect = await subject.handleDisconnect(
      CloseCode.AGENT_DEACTIVATED,
      "deactivated",
    );

    expect(reconnect).toBe(false);
    expect(refreshToken).not.toHaveBeenCalled();
    expect(register).not.toHaveBeenCalled();

    // The permanent stop must also short-circuit any later reconnect attempt.
    await subject.connectWithRetry();
    expect(getAccessToken).not.toHaveBeenCalled();
  });

  it("DUPLICATE_CONNECTION (4004): reconnects without refresh/register", async () => {
    const { subject, refreshToken, register } = makeSubject();

    const reconnect = await subject.handleDisconnect(
      CloseCode.DUPLICATE_CONNECTION,
      "duplicate",
    );

    expect(reconnect).toBe(true);
    expect(refreshToken).not.toHaveBeenCalled();
    expect(register).not.toHaveBeenCalled();
  });

  it("unknown code (e.g. 1006): reconnects without refresh/register", async () => {
    const { subject, refreshToken, register } = makeSubject();

    const reconnect = await subject.handleDisconnect(1006, "abnormal");

    expect(reconnect).toBe(true);
    expect(refreshToken).not.toHaveBeenCalled();
    expect(register).not.toHaveBeenCalled();
  });

  it("returns false for any code once stopped", async () => {
    const { subject, refreshToken, register } = makeSubject();

    subject.stop();
    const reconnect = await subject.handleDisconnect(
      CloseCode.TOKEN_EXPIRED,
      "expired",
    );

    expect(reconnect).toBe(false);
    expect(refreshToken).not.toHaveBeenCalled();
    expect(register).not.toHaveBeenCalled();
  });
});
