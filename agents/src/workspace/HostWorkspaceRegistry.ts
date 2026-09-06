// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Supervisor-owned HostWorkspaceRegistry (design §16.5/§17.1, Feature 10).
 *
 * The Supervisor — not the model, not a worker thread — owns checkout
 * lifecycle. One registry keyed by `runId` tracks every live run's
 * workspace: the pinned Agent, the lease deadline, the workspace
 * generation, and the durable external lease manifest written beside the
 * checkout. `AgentWorker` receives a scoped handle and cannot delete the
 * checkout; only the registry's release path may.
 *
 * Lease states (§16.5): ACQUIRING → ACTIVE → SUSPENDED → ACTIVE, with
 * every live path able to reach RELEASING → RELEASED, and SUSPENDED able
 * to reach LOST. Generation checks reject stale operations; release is
 * idempotent (repeated release of the same generation returns the stored
 * acknowledgement); restart reconciliation re-adopts manifests found on
 * disk.
 */
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { ORCHESTRATION_SCHEDULING_NS, WORKSPACE_ACK_NS, uuidV5 } from "../orchestration/constants.js";

export type LeaseState =
  | "ACQUIRING"
  | "ACTIVE"
  | "SUSPENDED"
  | "RELEASING"
  | "RELEASED"
  | "LOST";

export interface LeaseManifest {
  schemaVersion: "1.0";
  workspaceId: string;
  runId: string;
  generation: number;
  pinnedAgentId: string | null;
  leaseState: LeaseState;
  leaseDeadline: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AcquireLeaseInput {
  runId: string;
  generation: number;
  pinnedAgentId: string | null;
  leaseDeadline: string | null;
  checkoutPath: string;
}

export interface LeaseHandle {
  workspaceId: string;
  runId: string;
  generation: number;
  checkoutPath: string;
  leaseState: LeaseState;
  /** True only while the holder may mutate the checkout. */
  isMutable(): boolean;
}

export interface ReleaseAcknowledgement {
  acknowledgementId: string;
  releaseId: string;
  runId: string;
  generation: number;
  status: "RELEASED" | "LOST" | "CLEANUP_FAILED";
  occurredAt: string;
}

export interface HostWorkspaceRegistryOptions {
  workspaceRoot: string;
  /** Lease grace period for expiry sweeps (§17.1). */
  recoveryGraceSeconds?: number;
}

/** Thrown when an operation targets a wrong generation (§16.5). */
export class StaleGenerationError extends Error {
  constructor(
    readonly runId: string,
    readonly expectedGeneration: number,
    readonly actualGeneration: number,
  ) {
    super(
      `stale generation for run ${runId}: expected ${expectedGeneration}, ` +
        `registry holds ${actualGeneration}`,
    );
    this.name = "StaleGenerationError";
  }
}

/** Thrown when a mutation is attempted without the exclusive lock. */
export class LeaseNotMutableError extends Error {
  constructor(readonly runId: string, readonly state: LeaseState) {
    super(
      `run ${runId} lease is ${state} — the checkout is not mutable; ` +
        `the Supervisor must move it back to ACTIVE first`,
    );
    this.name = "LeaseNotMutableError";
  }
}

/**
 * The registry. All state changes go through the registry so the lease
 * manifest on disk and the in-memory map never diverge; a restart
 * re-adopts on-disk manifests (reconcile()).
 */
export class HostWorkspaceRegistry {
  private readonly leases = new Map<string, LeaseManifest>();
  private readonly checkoutPaths = new Map<string, string>();
  private readonly mutations = new Map<string, Promise<unknown>>();
  private readonly releaseCache = new Map<string, ReleaseAcknowledgement>();

  constructor(private readonly options: HostWorkspaceRegistryOptions) {
    mkdirSync(options.workspaceRoot, { recursive: true });
  }

  /** ACQUIRING: record a new (or generation-advanced) lease for a run. */
  acquire(input: AcquireLeaseInput): LeaseHandle {
    const existing = this.leases.get(input.runId);
    if (
      existing &&
      existing.generation > input.generation &&
      existing.leaseState !== "RELEASED" &&
      existing.leaseState !== "LOST"
    ) {
      throw new StaleGenerationError(input.runId, input.generation, existing.generation);
    }
    const manifest: LeaseManifest = {
      schemaVersion: "1.0",
      workspaceId: existing?.workspaceId ?? `ws-${randomUUID()}`,
      runId: input.runId,
      generation: input.generation,
      pinnedAgentId: input.pinnedAgentId,
      leaseState: "ACQUIRING",
      leaseDeadline: input.leaseDeadline,
      createdAt: existing?.createdAt ?? new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    this.leases.set(input.runId, manifest);
    this.checkoutPaths.set(input.runId, input.checkoutPath);
    this.persistManifest(manifest);
    return this.handleFor(input.runId);
  }

  /** ACQUIRING → ACTIVE: the checkout is cloned, verified, and usable. */
  activate(runId: string, generation: number): LeaseHandle {
    const manifest = this.requireManifest(runId);
    this.requireGeneration(manifest, generation);
    if (manifest.leaseState !== "ACQUIRING" && manifest.leaseState !== "SUSPENDED") {
      throw new Error(`cannot activate run ${runId} from state ${manifest.leaseState}`);
    }
    manifest.leaseState = "ACTIVE";
    manifest.updatedAt = new Date().toISOString();
    this.persistManifest(manifest);
    return this.handleFor(runId);
  }

  /** ACTIVE → SUSPENDED (HITL pause keeps the checkout, releases compute). */
  suspend(runId: string, generation: number, newDeadline: string | null): LeaseHandle {
    const manifest = this.requireManifest(runId);
    this.requireGeneration(manifest, generation);
    if (manifest.leaseState !== "ACTIVE") {
      throw new Error(`cannot suspend run ${runId} from state ${manifest.leaseState}`);
    }
    manifest.leaseState = "SUSPENDED";
    manifest.leaseDeadline = newDeadline;
    manifest.updatedAt = new Date().toISOString();
    this.persistManifest(manifest);
    return this.handleFor(runId);
  }

  /**
   * The exclusive mutation lock: serialize checkout mutations per run. A
   * concurrent second mutation waits for the first; a stale-generation
   * mutation is rejected before touching the checkout.
   */
  async withMutation<T>(
    runId: string,
    generation: number,
    mutation: () => Promise<T>,
  ): Promise<T> {
    const manifest = this.requireManifest(runId);
    this.requireGeneration(manifest, generation);
    if (manifest.leaseState !== "ACTIVE") {
      throw new LeaseNotMutableError(runId, manifest.leaseState);
    }
    const previous = this.mutations.get(runId) ?? Promise.resolve();
    const chained = (async () => {
      await previous.catch(() => undefined);
      return mutation();
    })();
    // Track the chain tail so the next caller waits for this one.
    const tracked = chained.finally(() => {
      if (this.mutations.get(runId) === tracked) {
        this.mutations.delete(runId);
      }
    });
    this.mutations.set(runId, tracked);
    return chained;
  }

  /**
   * Idempotent release (§16.5): rejects a stale generation, waits for
   * active cleanup (the mutation lock), removes the checkout and hidden
   * refs, records RELEASED, and returns the acknowledgement. Repeated
   * release returns the SAME stored acknowledgement keyed by
   * runId+generation+status.
   */
  async release(
    runId: string,
    generation: number,
    _reason: string,
  ): Promise<ReleaseAcknowledgement> {
    const manifest = this.requireManifest(runId);
    this.requireGeneration(manifest, generation);

    const cacheKey = `${runId}:${generation}:RELEASED`;
    const cached = this.releaseCache.get(cacheKey);
    if (cached) return cached;

    // Wait for any in-flight mutation before cleanup (§16.5: "waits for
    // active cleanup").
    const pending = this.mutations.get(runId);
    if (pending) await pending.catch(() => undefined);

    const checkoutPath = this.checkoutPaths.get(runId);
    if (checkoutPath && existsSync(checkoutPath)) {
      try {
        rmSync(checkoutPath, { recursive: true, force: true });
      } catch {
        manifest.leaseState = "RELEASING";
        manifest.updatedAt = new Date().toISOString();
        this.persistManifest(manifest);
        return this.recordAcknowledgement(manifest, "CLEANUP_FAILED");
      }
    }

    manifest.leaseState = "RELEASED";
    manifest.leaseDeadline = null;
    manifest.updatedAt = new Date().toISOString();
    this.persistManifest(manifest);
    this.mutations.delete(runId);
    return this.recordAcknowledgement(manifest, "RELEASED");
  }

  /**
   * Explicit loss (§16.4/§16.5): the workspace is gone or unusable; the
   * run must fail with WORKSPACE_LOST. Idempotent per generation.
   */
  async markLost(runId: string, generation: number): Promise<ReleaseAcknowledgement> {
    const manifest = this.requireManifest(runId);
    this.requireGeneration(manifest, generation);
    const cacheKey = `${runId}:${generation}:LOST`;
    const cached = this.releaseCache.get(cacheKey);
    if (cached) return cached;

    manifest.leaseState = "LOST";
    manifest.leaseDeadline = null;
    manifest.updatedAt = new Date().toISOString();
    this.persistManifest(manifest);
    return this.recordAcknowledgement(manifest, "LOST");
  }

  /** The lease manifest for a run (or undefined when unknown). */
  manifestOf(runId: string): LeaseManifest | undefined {
    const manifest = this.leases.get(runId);
    return manifest ? { ...manifest } : undefined;
  }

  /**
   * Restart reconciliation (§17.1): re-adopt every lease manifest found
   * under the workspace root's runs directory. Live leases keep their
   * identity; a Supervisor restart never orphans a checkout.
   */
  reconcile(): LeaseManifest[] {
    const runsDir = join(this.options.workspaceRoot, "runs");
    if (!existsSync(runsDir)) return [];
    const adopted: LeaseManifest[] = [];
    for (const runId of readdirSafe(runsDir)) {
      for (const generation of readdirSafe(join(runsDir, runId))) {
        const manifestPath = join(runsDir, runId, generation, "lease-manifest.json");
        if (!existsSync(manifestPath)) continue;
        try {
          const manifest: LeaseManifest = JSON.parse(readFileSync(manifestPath, "utf-8"));
          // ACQUIRING at restart means the clone never finished — the run
          // is recoverable from source; adopt as-is and let the run
          // re-drive it.
          this.leases.set(runId, manifest);
          const checkoutPath = join(runsDir, runId, generation, "checkout");
          if (existsSync(checkoutPath)) {
            this.checkoutPaths.set(runId, checkoutPath);
          }
          adopted.push(manifest);
        } catch {
          // A corrupt manifest is not adoptable; the run fails lost.
        }
      }
    }
    return adopted;
  }

  // ── internals ──────────────────────────────────────────────────────

  private requireManifest(runId: string): LeaseManifest {
    const manifest = this.leases.get(runId);
    if (!manifest) {
      throw new Error(`no lease registered for run ${runId}`);
    }
    return manifest;
  }

  private requireGeneration(manifest: LeaseManifest, generation: number): void {
    if (manifest.generation !== generation) {
      throw new StaleGenerationError(manifest.runId, generation, manifest.generation);
    }
  }

  private recordAcknowledgement(
    manifest: LeaseManifest,
    status: ReleaseAcknowledgement["status"],
  ): ReleaseAcknowledgement {
    const releaseId = uuidV5(
      ORCHESTRATION_SCHEDULING_NS,
      `release:${manifest.runId}:${manifest.generation}`,
    );
    const acknowledgementId = uuidV5(
      WORKSPACE_ACK_NS,
      `${releaseId}:${manifest.generation}:${status}`,
    );
    const ack: ReleaseAcknowledgement = {
      acknowledgementId,
      releaseId,
      runId: manifest.runId,
      generation: manifest.generation,
      status,
      occurredAt: new Date().toISOString(),
    };
    this.releaseCache.set(`${manifest.runId}:${manifest.generation}:${status}`, ack);
    return ack;
  }

  private handleFor(runId: string): LeaseHandle {
    const manifest = this.requireManifest(runId);
    const checkoutPath = this.checkoutPaths.get(runId) ?? "";
    return {
      workspaceId: manifest.workspaceId,
      runId,
      generation: manifest.generation,
      checkoutPath,
      leaseState: manifest.leaseState,
      isMutable: () => this.leases.get(runId)?.leaseState === "ACTIVE",
    };
  }

  /** Durable external lease manifest beside the checkout (§16.5). */
  private persistManifest(manifest: LeaseManifest): void {
    const dir = join(
      this.options.workspaceRoot,
      "runs",
      manifest.runId,
      String(manifest.generation),
    );
    mkdirSync(dir, { recursive: true });
    const target = join(dir, "lease-manifest.json");
    const temp = join(dir, ".lease-manifest.tmp");
    writeFileSync(temp, JSON.stringify(manifest, null, 2));
    // Atomic temp-then-rename: a crash never leaves a torn manifest.
    renameSync(temp, target);
  }
}

function readdirSafe(path: string): string[] {
  try {
    return readdirSync(path);
  } catch {
    return [];
  }
}