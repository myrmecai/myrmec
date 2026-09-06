// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Orchestration package entry point. The runner, tools, and workspace
 * components land in later features; this first slice freezes the DSL
 * schema and the compiled assignment contract.
 */
export * from "./types.js";
export {
  workflowDefinitionSchema,
  orchestrationAssignmentSchema,
  compileStepAssignment,
  createCredentialScope,
  type CompileStepAssignmentInput,
  type CredentialScope,
} from "./schema.js";
export { OrchestrationRunner, type OrchestrationRunnerOptions, type OrchestrationRunOptions } from "./OrchestrationRunner.js";
export { WorkerInvoker, normalizeUsage, type WorkerInvokerOptions, type InvokeWorkerOutcome } from "./WorkerInvoker.js";
export {
  InMemoryVerificationLedger,
  toVerifierResult,
  type VerificationLedger,
  type VerdictRecord,
} from "./VerificationLedger.js";
export type { CheckoutHandle, StepWorkspace, WorkspaceManager, WorkspaceScope } from "../workspace/WorkspaceManager.js";
export { GitWorkspaceManager, GitWorkspaceScope, WorkspaceError, confinePath } from "../workspace/GitWorkspaceManager.js";
export type { WorkspaceInspector, CandidateTree } from "../workspace/WorkspaceInspector.js";
export { GitWorkspaceInspector } from "../workspace/WorkspaceInspector.js";