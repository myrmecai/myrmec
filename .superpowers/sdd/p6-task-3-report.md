# Plan 6 (rewritten) — Task 3 Report

## Status

**DONE**

## Scope executed

- Delete legacy outbound emission branches from the SDK executor/worker layer:
  - `INFERENCE_DELTA` / `INFERENCE_COMPLETE` / `INFERENCE_FAILED`
  - `INFERENCE_TOOL_CALL` / `INFERENCE_TOOL_RESULT`
  - `MESSAGE_DELTA` / `MESSAGE_COMPLETE`
- Replace them with unified `execution.*` outbound frames:
  - `execution.accept` / `execution.reject`
  - `execution.delta` / `execution.event`
  - `execution.complete` / `execution.failed` / `execution.paused` / `execution.cancelled`
  - `execution.approval.requested`
- Update executor/worker tests to assert the unified emissions.
- Keep changes within `agents/src/executor/*`, `agents/src/worker/*`, and `agents/src/supervisor/HostControlClient.ts` (send helpers only), per the task boundary.

## Commit

- `d3d0b7d` — `feat(sdk): executor/worker emit unified execution.* frames; drop legacy outbound inference/message branches`

## Files changed

- `agents/src/executor/ExecutionFrameSender.ts` (created)
- `agents/src/executor/InferenceExecutor.ts` (rewritten to unified emissions)
- `agents/src/executor/ApprovalCoordinator.ts` (dual-mode unified/legacy approvals)
- `agents/src/supervisor/HostControlClient.ts` (added `sendExecutionApprovalRequested`)
- `agents/src/worker/agentWorker.ts` (unified sender wrapper + `execution.start`/`execution.cancel` dispatch)
- `agents/src/executor/InferenceExecutor.test.ts` (rewritten for unified frame assertions)
- `agents/src/worker/agentWorker.test.ts` (repointed to `execution.start` → `execution.delta`/`execution.complete`)

## Verification

### SDK tests

```text
> @myrmec/agent@0.1.0 test
> vitest run

 Test Files  38 passed | 1 failed (39)
      Tests  401 passed | 1 failed (402)
   Start at  20:59:50
   Duration  31.86s
```

The single remaining failure is the pre-existing `AgentOrchestrationExecutor.test.ts` stub-module-path artifact:

```text
Error: Cannot find module 'file:///C:/gitrepos/myrmecai/myrmec/.worktrees/myrmec-ee/e2e/fixtures/stubs/orchestration-minimal.ts'
```

This failure is outside the Task 3 scope (orchestration outbox/sink mapping is covered later and the missing stub path is a pre-existing worktree-layout artifact). `agentWorker.test.ts` is now green after repointing to unified emissions.

### SDK typecheck

```text
> @myrmec/agent@0.1.0 typecheck
> tsc --noEmit
(no errors)
```

### SDK build

```text
> @myrmec/agent@0.1.0 build
> tsc -p tsconfig.build.json && node scripts/build-cjs.cjs && node scripts/rename-cjs.cjs

CJS build written to C:\gitrepos\myrmecai\myrmec\.worktrees\unified-agent-protocol\agents\dist\cjs
```

### Lint on changed files

No ESLint errors in the Task 3 files. The repository-wide `npm run lint` still reports 4 pre-existing errors in `scripts/build-cjs.cjs` and `scripts/rename-cjs.cjs` (forbidden `require()` in `.cjs` build scripts), untouched by this task.

## Key implementation notes

- Created `ExecutionFrameSender` as a typed outbound seam so `InferenceExecutor` no longer constructs legacy `Envelope` frames directly.
- `InferenceExecutor` now accepts `ExecutionStartPayload` via `handleStart()` and emits only `execution.*` frames; the tool loop keeps tool calls/results internal.
- `ApprovalCoordinator` emits `execution.approval.requested` when an `executionId` and unified sender are wired, falling back to legacy `approval.request` otherwise.
- `AgentWorker` builds an `ExecutionFrameSender` wrapper around its `post` sink, handles unified `execution.start`/`execution.cancel`, and still accepts legacy `inference.assign`/`inference.cancel` inbound (mapped to unified payloads) until the engine cutover lands in Tasks 4–5.
- `HostControlClient` gained `sendExecutionApprovalRequested(payload)` so the control-socket send surface is complete.

## Concerns / deviations

- The `.superpowers/sdd/p6-task-3-brief.md` file in the worktree is corrupted/mis-copied and does not contain a dedicated Task 3 section; the Task 3 requirements were reconstructed from the conversation context and the user's explicit instruction to update executor/worker outbound emissions and tests.
- Orchestration emissions (`orchestration.event`, `orchestration.result`, `orchestration.approval_requested` from `OrchestrationOutbox`/`AgentProtocolOrchestrationEventSink`) were intentionally not remapped to `execution.*` because those files live in `agents/src/protocol/`, which is outside the user-specified Task 3 boundary (`agents/src/executor/*`, `agents/src/worker/*`, `agents/src/supervisor/HostControlClient.ts`). That mapping remains for a later task.

## Next task needs

- Task 4/5 engine dispatcher cutover (`ConversationTurnDispatcher`, `TaskDispatcherService`) can now assume the SDK side consumes unified `execution.*` frames.
- Task 6 legacy deletion can later remove the remaining legacy `MessageType` entries and old frame builders once the engine no longer sends them.
