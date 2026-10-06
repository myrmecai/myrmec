# SPDX-License-Identifier: Apache-0
# (working patch script - remove after use)
# Repairs the truncated suspensionPublisher block in OrchestrationExecutor.ts.
$ErrorActionPreference = "Stop"
$path = "c:\gitrepos\myrmecai\myrmec\agents\src\worker\OrchestrationExecutor.ts"
$raw = [System.IO.File]::ReadAllText($path)

$broken = "        suspensionPublisher: {`r`n`r`n      // Task 5 fixes 1+5"

# NOTE: double-quoted here-string; backticks escape literal backticks,
# $(...) is escaped as `$(...) where the TS template needs a literal.
$tail = @"
          publish: async (input) => {
            // The manifest binds the FULL suspension identity - pending
            // action, approval request id, expiry - so the resume
            // validation restores exactly what the human approved.
            const store = new LocalContinuationStateStore(
              path.join(this.options.outboxRoot, "continuations"),
            );
            const full = store.put({
              continuationId: ``cont-`$(input.dispatch.dispatchId)-hitl``,
              dispatchId: input.dispatch.dispatchId,
              attemptOrdinal: input.dispatch.attemptOrdinal,
              budgetCounters: {
                helperCalls: 0,
                totalTokens: 0,
                rejectionCount: 0,
              },
              completedCallIds: [],
              candidateTreeHash: input.candidateTreeHash,
              workspaceRevision: input.workspaceRevision,
              verifierHistory: [],
              pendingAction: {
                actionId: input.action.actionId,
                type: input.action.type,
                riskClass: input.action.riskClass,
                summary: input.action.summary,
                digest: input.action.digest,
              },
              approvalRequestId: input.approvalRequestId,
              suspensionExpiresAt: input.expiresAt,
              createdAt: new Date().toISOString(),
            });
            return {
              continuationId: full.continuationId,
              continuationRef: ``local:`$(full.continuationId)``,
              snapshotTreeHash: full.candidateTreeHash,
              stateDigest: full.stateDigest,
            };
          },
        },
        suspensionLoader: {
          load: (continuationId) => {
            // Restore the stored suspension for the decision validation -
            // the manifest carries the full identity (pending action,
            // request id, expiry) the typed decision envelope binds
            // against.
            const store = new LocalContinuationStateStore(
              path.join(this.options.outboxRoot, "continuations"),
            );
            const manifest = store.get(continuationId);
            if (!manifest || !manifest.pendingAction || !manifest.approvalRequestId) {
              return null;
            }
            return {
              continuationId: manifest.continuationId,
              previousDispatchId: manifest.dispatchId,
              suspension: {
                continuationId: manifest.continuationId,
                continuationRef: ``local:`$(manifest.continuationId)``,
                snapshotTreeHash: manifest.candidateTreeHash,
                workspaceRevision: manifest.workspaceRevision,
                stateDigest: manifest.stateDigest,
                reason: "HITL_APPROVAL" as const,
                approvalRequestId: manifest.approvalRequestId,
                pendingAction: manifest.pendingAction,
                expiresAt: manifest.suspensionExpiresAt,
              },
              manifest,
            };
          },
        },
        // The retry-continuation publication + restore - RETRYABLE safe
        // boundaries persist the manifest; the engine's retry dispatch
        // (decision-less continuation) restores completed-call identities
        // + verifier history here.
        retryContinuationPublisher: {
          publish: async (input) => {
            const store = new LocalContinuationStateStore(
              path.join(this.options.outboxRoot, "continuations"),
            );
            const full = store.put({
              continuationId: ``cont-`$(input.dispatchId)-retry``,
              dispatchId: input.dispatchId,
              attemptOrdinal: input.attemptOrdinal,
              budgetCounters: input.budgetCounters,
              completedCallIds: input.completedCalls
                .filter((c) => c.status === "COMPLETED")
                .map((c) => c.callId),
              candidateTreeHash: input.candidateTreeHash,
              workspaceRevision: input.workspaceRevision,
              verifierHistory: input.verifierHistory,
              createdAt: new Date().toISOString(),
            });
            return {
              continuationId: full.continuationId,
              continuationRef: ``local:`$(full.continuationId)``,
              stateDigest: full.stateDigest,
            };
          },
        },
        retryContinuationLoader: {
          load: (continuationId) => {
            const store = new LocalContinuationStateStore(
              path.join(this.options.outboxRoot, "continuations"),
            );
            return store.get(continuationId);
          },
        },
        // Task 7: the composed hold gate - the runner's helper
        // admission (BEFORE_HELPER_CALL) and the final Git checkpoint
        // (BEFORE_GIT_EFFECT) park while HELD; the turn-level
        // boundaries ride the SAME attempt scheduler.
        ...(composerCoordinator ? { toolGate: composerCoordinator } : {}),
      });
"@

if ($raw.Contains($broken)) {
  $raw = $raw.Replace($broken, "        suspensionPublisher: {" + "`r`n" + $tail)
  [System.IO.File]::WriteAllText($path, $raw, [System.Text.UTF8Encoding]::new($false))
  Write-Host "PATCHED OK"
} else {
  Write-Host "ANCHOR NOT FOUND"
}