// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * WebSocket close codes for host↔engine communication. The 4xxx codes drive
 * the host-control client's reconnect/re-register reaction (REQ-A-002) — the
 * same matrix the legacy supervisor loop ran, now inside
 * `ReconnectingConnection` under `HostControlClient.start()`.
 */

/**
 * WebSocket close codes for host communication. The 4xxx codes drive the
 * reconnect/re-register reaction (REQ-A-002).
 */
export const CloseCode = {
  NORMAL: 1000, // Normal closure
  GOING_AWAY: 1001, // Server shutdown
  TOKEN_EXPIRED: 4001, // Token expired — refresh and reconnect
  INVALID_TOKEN: 4002, // Invalid token — re-register
  AGENT_DEACTIVATED: 4003, // Agent disabled by admin
  DUPLICATE_CONNECTION: 4004, // Another instance connected
} as const;

export type CloseCode = (typeof CloseCode)[keyof typeof CloseCode];