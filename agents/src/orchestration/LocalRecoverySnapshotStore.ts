// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * HOST_LOCAL recovery provider (design §17.2, Feature 10): artifacts
 * stored outside the checkout under
 * `<workspaceRoot>/recovery/<runId>/<generation>/`. Writes go through a
 * temporary name and publish atomically only after digest verification;
 * reads verify the digest before returning bytes; the store is separate
 * from the live workspace path and never moves the target branch, runs
 * hooks, or publishes source.
 */
import {
  createHash,
} from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  type RecoverySnapshotStore,
  type SnapshotIdentity,
  type StoredSnapshot,
} from "./RecoverySnapshotStore.js";

export interface LocalRecoverySnapshotStoreOptions {
  workspaceRoot: string;
}

export class LocalRecoverySnapshotStore implements RecoverySnapshotStore {
  readonly providerId = "host-local";
  readonly providerVersion = "1";

  constructor(private readonly options: LocalRecoverySnapshotStoreOptions) {}

  /** The configuration digest the engine pins per run (§17.2). */
  configDigest(): string {
    return createHash("sha256")
      .update(`${this.providerId}:${this.providerVersion}:${this.options.workspaceRoot}`)
      .digest("hex");
  }

  async put(identity: SnapshotIdentity, bytes: Uint8Array, digest: string): Promise<void> {
    const actual = createHash("sha256").update(bytes).digest("hex");
    if (actual !== digest) {
      throw new Error(
        `snapshot ${identity.snapshotId} digest mismatch on write — refusing to publish`,
      );
    }
    const dir = this.dirOf(identity);
    mkdirSync(dir, { recursive: true });
    const target = join(dir, `${identity.snapshotId}.artifact`);
    const temp = join(dir, `.${identity.snapshotId}.tmp`);
    writeFileSync(temp, bytes);
    // Atomic publish ONLY after digest verification (§17.2).
    renameSync(temp, target);
  }

  async get(identity: SnapshotIdentity): Promise<StoredSnapshot | null> {
    const target = join(this.dirOf(identity), `${identity.snapshotId}.artifact`);
    if (!existsSync(target)) return null;
    const bytes = new Uint8Array(readFileSync(target));
    const digest = createHash("sha256").update(bytes).digest("hex");
    return { identity, digest, bytes };
  }

  async delete(identity: SnapshotIdentity): Promise<void> {
    rmSync(join(this.dirOf(identity), `${identity.snapshotId}.artifact`), { force: true });
  }

  async deleteRun(runId: string): Promise<void> {
    const runDir = join(this.options.workspaceRoot, "recovery", runId);
    if (existsSync(runDir)) {
      rmSync(runDir, { recursive: true, force: true });
    }
  }

  /** Adoptable artifacts of one run (recovery point inventory). */
  listRun(runId: string): { generation: number; snapshotIds: string[] }[] {
    const runDir = join(this.options.workspaceRoot, "recovery", runId);
    if (!existsSync(runDir)) return [];
    const out: { generation: number; snapshotIds: string[] }[] = [];
    for (const gen of readdirSafe(runDir)) {
      const generation = Number.parseInt(gen, 10);
      if (!Number.isFinite(generation)) continue;
      const snapshotIds = readdirSafe(join(runDir, gen))
        .filter((n) => n.endsWith(".artifact"))
        .map((n) => n.slice(0, -".artifact".length));
      if (snapshotIds.length > 0) out.push({ generation, snapshotIds });
    }
    return out;
  }

  private dirOf(identity: SnapshotIdentity): string {
    return join(
      this.options.workspaceRoot,
      "recovery",
      identity.runId,
      String(identity.generation),
    );
  }
}

function readdirSafe(path: string): string[] {
  try {
    return readdirSync(path);
  } catch {
    return [];
  }
}