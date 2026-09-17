// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Outbound surface used by the unified executor bridge.
 *
 * Implementations live in the transport layer (HostControlClient) and in
 * test fakes. The executor no longer constructs legacy Envelope frames;
 * it hands strongly-typed execution payloads to this sender.
 */
import type {
  ExecutionAcceptPayload,
  ExecutionCancelPayload,
  ExecutionCancelledPayload,
  ExecutionCompletePayload,
  ExecutionDeltaPayload,
  ExecutionEventPayload,
  ExecutionFailedPayload,
  ExecutionPausedPayload,
  ExecutionApprovalRequestedPayload,
  ExecutionRejectPayload,
  ProtocolErrorPayload,
} from "../protocol/unifiedFrames.js";

export interface ExecutionFrameSender {
  sendExecutionAccept(payload: ExecutionAcceptPayload): Promise<void>;
  sendExecutionReject(payload: ExecutionRejectPayload): Promise<void>;
  sendExecutionDelta(payload: ExecutionDeltaPayload): Promise<void>;
  sendExecutionEvent(payload: ExecutionEventPayload): Promise<void>;
  sendExecutionComplete(payload: ExecutionCompletePayload): Promise<void>;
  sendExecutionFailed(payload: ExecutionFailedPayload): Promise<void>;
  sendExecutionPaused(payload: ExecutionPausedPayload): Promise<void>;
  sendExecutionCancelled(payload: ExecutionCancelledPayload): Promise<void>;
  sendExecutionCancel(payload: ExecutionCancelPayload): Promise<void>;
  sendExecutionApprovalRequested(
    payload: ExecutionApprovalRequestedPayload,
  ): Promise<void>;
  /** §8.7 (A4): answer a rejected execution.policy.update. */
  sendProtocolError(payload: ProtocolErrorPayload): Promise<void>;
}
