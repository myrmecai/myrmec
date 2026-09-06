// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Orchestration type definitions (design §7 public contracts).
 *
 * The exact runtime types are Zod-derived in schema.ts; these interfaces
 * mirror them for direct import ergonomics. The wire and module boundaries
 * are these shapes — never the authoring YAML.
 */
import type { z } from "zod";
import type {
  workflowDefinitionSchema,
  orchestrationAssignmentSchema,
} from "./schema.js";

// ── authoring (fixture DSL, design §6) ───────────────────────────────

export interface ModelAuthoring {
  code: string;
  provider: string;
  modelId: string;
  description: string;
  apiEndpoint: string | null;
  apiKey: string;
  parameters: Record<string, unknown>;
}

export type OrchestrationToolName =
  | "read_file"
  | "list_directory"
  | "create_directory"
  | "write_file"
  | "execute_command";

export const ORCHESTRATION_TOOL_NAMES: readonly OrchestrationToolName[] = [
  "read_file",
  "list_directory",
  "create_directory",
  "write_file",
  "execute_command",
] as const;

export type RiskClass = "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE";

export interface RetryPolicy {
  maxRetries: number;
  initialBackoffSeconds: number;
  maxBackoffSeconds: number;
}

export interface WorkerAuthoring {
  name: string;
  modelCode: string;
  capability: string;
  allowedTools: OrchestrationToolName[];
  allowedCommands: string[];
}

export interface OrchestrationPolicyAuthoring {
  modelCode: string;
  goal: string;
  specPath: string | null;
  sourceSubPath: string;
  workers: WorkerAuthoring[];
  checkpointStrategy: {
    mode: "ON_VERIFICATION_PASS";
    commitMessage: string;
    pushToRemote: boolean;
    allowNoChanges: boolean;
  };
  completionCriteria: {
    definitionOfDone: string;
    requireVerificationBy: string[];
  };
  budget: {
    maxTokens: number;
    maxWorkerCalls: number;
    maxVerifierRejectionsPerAttempt: number;
    maxOrchestratorIterations: number;
    maxWorkerIterations: number;
    onBudgetExceeded: "FAIL" | "PAUSE_FOR_HUMAN_REVIEW";
  };
}

export interface OrchestrationStepAuthoring {
  id: string;
  name: string;
  taskType: "ORCHESTRATOR";
  agentProfileCode: string;
  dependsOn: string[];
  retryPolicy: RetryPolicy;
  orchestration: OrchestrationPolicyAuthoring;
}

export interface WorkflowDefinitionAuthoring {
  version: "1.0";
  id: string;
  name: string;
  source: {
    repoUrl: string;
    sourceBranch: string;
    targetBranch: string;
    accessToken: string;
  };
  workflow: OrchestrationStepAuthoring[];
}

export type WorkflowDefinition = z.infer<typeof workflowDefinitionSchema>;

// ── runtime (assignment, design §7) ──────────────────────────────────

/** Source resolved by the adapter/engine: authoring fields plus the
 * immutable object ID the Agent fetches and checks out. */
export interface ResolvedSource {
  repoUrl: string;
  sourceBranch: string;
  sourceBaseCommit: string;
  targetBranch: string;
  accessToken: string;
}

export interface CommandTemplateDefinition {
  executable: string;
  args: Array<string | { parameter: string }>;
  parameters: Record<
    string,
    {
      type: "string" | "integer" | "boolean";
      required: boolean;
      pattern?: string;
      allowedValues?: Array<string | number | boolean>;
    }
  >;
  cwdPattern: string;
  environmentAllowlist: string[];
  timeoutSeconds: number;
  maxOutputBytes: number;
  network: "DENY" | "ALLOW";
  maxCpuSeconds: number;
  maxMemoryBytes: number;
  /** Optional risk override; defaults to SAFE for command templates. */
  riskClass?: RiskClass;
}

export interface ExecutionPolicy {
  allowedTools: OrchestrationToolName[];
  commandTemplates: Record<string, CommandTemplateDefinition>;
  requiredIsolation: "TRUSTED_PROCESS" | "UNTRUSTED_REPOSITORY";
  approvalPolicy: Record<string, "ALLOW" | "DENY" | "REQUIRE_APPROVAL">;
  gitPolicy: { allowCheckpoint: boolean; allowPush: boolean };
  workspaceRetentionSeconds: number;
  approvalRequestTtlSeconds?: number;
}

export interface ModelDefinition {
  code: string;
  provider: string;
  modelId: string;
  description: string;
  apiEndpoint: string | null;
  credentialRef: string | null;
  parameters: Record<string, unknown>;
}

export interface SourceDefinition {
  repoUrl: string;
  sourceBranch: string;
  sourceBaseCommit: string;
  targetBranch: string;
  credentialRef: string | null;
}

export interface DispatchIdentity {
  workflowId: string;
  runId: string;
  stepId: string;
  taskId: string;
  attemptId: string;
  attemptOrdinal: number;
  dispatchId: string;
  continuationId?: string;
}

export interface ContinuationDirective {
  continuationId: string;
  previousDispatchId: string;
}

export interface OrchestrationAssignment {
  schemaVersion: "1.0";
  dispatch: DispatchIdentity;
  models: ModelDefinition[];
  source: SourceDefinition;
  policy: ExecutionPolicy;
  step: OrchestrationStepAuthoring;
  continuation?: ContinuationDirective;
}

export type OrchestrationAssignmentRuntime = z.infer<
  typeof orchestrationAssignmentSchema
>;

// ── run result (design §7.3 subset for the minimal delegation feature) ──

export type OrchestrationRunStatus = "COMPLETED" | "FAILED" | "CANCELLED" | "PAUSED";

export type OrchestrationErrorCode =
  | "ASSIGNMENT_VALIDATION_ERROR"
  | "ORCHESTRATOR_FAILED"
  | "ORCHESTRATOR_ITERATION_LIMIT"
  | "WORKER_FAILED"
  | "WORKER_ITERATION_LIMIT"
  | "WORKER_BUDGET_EXCEEDED"
  | "TOKEN_BUDGET_EXCEEDED"
  | "TOKEN_USAGE_UNAVAILABLE"
  | "REJECTION_BUDGET_EXCEEDED"
  | "INVALID_VERIFIER_RESULT"
  | "PATH_OUTSIDE_WORKSPACE"
  | "COMMAND_NOT_ALLOWED"
  | "COMMAND_SANDBOX_UNAVAILABLE"
  | "WORKSPACE_ERROR"
  | "SPECIFICATION_MUTATED"
  | "WORKSPACE_LOST"
  | "WORKSPACE_EXPIRED"
  | "SOURCE_BASE_UNAVAILABLE"
  | "RECOVERY_SNAPSHOT_INVALID"
  | "CHECKPOINT_FAILED"
  | "CHECKPOINT_NO_CHANGES"
  | "PUSH_FAILED"
  | "PUSH_CONFLICT"
  | "APPROVAL_REJECTED"
  | "APPROVAL_EXPIRED"
  | "APPROVAL_POLICY_DENIED"
  | "INTERNAL_ORCHESTRATION_ERROR";

export type RetryDisposition = "NONE" | "RETRYABLE" | "TERMINAL";

export type WorkerCallStatus = "COMPLETED" | "FAILED" | "CANCELLED";

export interface WorkerCallResult {
  callId: string;
  workerName: string;
  purpose: "IMPLEMENT" | "VERIFY";
  status: WorkerCallStatus;
  startedSequence: number;
  completedSequence: number;
  workspaceRevisionBefore: number;
  workspaceRevisionAfter: number;
  tokenCount: number;
  errorCode?: OrchestrationErrorCode;
}

/** A structured verifier verdict bound to its exact candidate tree
 * (design §7.3). Every identity field is runner-owned. */
export interface VerifierResult {
  callId: string;
  workerName: string;
  verdict: "APPROVED" | "REJECTED";
  summary: string;
  issues: string[];
  workspaceRevision: number;
  candidateTreeHash: string;
  attemptOrdinal: number;
  sequence: number;
}

/** One bounded command execution, evidence only (design §7.3). */
export interface CommandExecutionRecord {
  commandId: string;
  workerCallId: string;
  templateName: string;
  executable: string;
  argsDigest: string;
  workspaceRelativeCwd: string;
  exitCode: number | null;
  timedOut: boolean;
  cancelled: boolean;
  outputTruncated: boolean;
}

/** The §7.3 checkpoint commit evidence returned in the run result. */
export interface CheckpointCommit {
  idempotencyKey: string;
  commitHash: string;
  treeHash: string;
  parentHash: string;
  message: string;
  files: string[];
}

export interface OrchestrationRunResult {
  schemaVersion: "1.0";
  resultId: string;
  resultDigest: string;
  dispatch: DispatchIdentity;
  status: OrchestrationRunStatus;
  retryDisposition: RetryDisposition;
  summary: string;
  workerCalls: WorkerCallResult[];
  verifierResults: VerifierResult[];
  commandExecutions: CommandExecutionRecord[];
  changedFiles: string[];
  commits: CheckpointCommit[];
  cleanWorktree: boolean; // Feature 6
  usage: {
    workerCalls: number;
    rejectionCount: number;
    totalTokens: number;
  };
  continuation?: unknown; // Feature 7
  suspension?: unknown; // Feature 7
  errorCode?: OrchestrationErrorCode;
}