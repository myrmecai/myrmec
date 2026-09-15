// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Shared wire types for the unified dispatch protocol (P6-T6).
 *
 * `ModelInfoWire` and `ToolDefinition` previously lived in the legacy
 * taskFrames.ts / inferenceFrames.ts builders (deleted with the legacy agent
 * wire). They are pure engine→SDK descriptor shapes still consumed by the
 * model resolver, executors, session registry, and orchestration runner —
 * they live here, with their zod twins in unifiedFrames.ts.
 */

/** LLM model config attached to a session (engine `SessionOpenPayload.ModelConfig`). */
export interface ModelInfoWire {
  provider: string;
  modelId: string;
  apiEndpoint: string | null;
  apiKey: string | null;
  parameters: Record<string, unknown>;
}

/** A tool the engine declares available for a session. */
export interface ToolDefinition {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
  riskClass: "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE";
}