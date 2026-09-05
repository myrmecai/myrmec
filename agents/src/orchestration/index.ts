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