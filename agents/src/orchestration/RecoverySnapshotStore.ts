// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Opaque streaming recovery-store contract (design §17.2, Feature 10):
 * put/get/delete of opaque artifacts keyed by identity + digest. The
 * engine stores only identity/digest/lease metadata; bytes live in the
 * provider. `LocalRecoverySnapshotStore` implements HOST_LOCAL under the
 * Host workspace root; provider names SHARED_VOLUME, INTERNAL_GIT,
 * CUSTOMER_GIT, OBJECT_STORAGE are reserved and fail configuration
 * validation rather than silently falling back.
 */

export interface SnapshotIdentity {
  runId: string;
  generation: number;
  /** The opaque artifact id (continuation/snapshot id). */
  snapshotId: string;
}

export interface StoredSnapshot {
  identity: SnapshotIdentity;
  /** SHA-256 over the artifact bytes — verified on both write and read. */
  digest: string;
  bytes: Uint8Array;
}

export interface RecoverySnapshotStore {
  readonly providerId: string;
  readonly providerVersion: string;
  put(identity: SnapshotIdentity, bytes: Uint8Array, digest: string): Promise<void>;
  get(identity: SnapshotIdentity): Promise<StoredSnapshot | null>;
  delete(identity: SnapshotIdentity): Promise<void>;
  /** Delete every artifact of one run after terminal release (§17.2). */
  deleteRun(runId: string): Promise<void>;
}

/** The supported provider types in V1 (§17.2). */
export const SUPPORTED_RECOVERY_PROVIDER_TYPES = ["HOST_LOCAL"] as const;
export type RecoveryProviderType = (typeof SUPPORTED_RECOVERY_PROVIDER_TYPES)[number];

/** Reserved provider names that must fail validation, not fall back. */
export const RESERVED_RECOVERY_PROVIDER_TYPES = [
  "SHARED_VOLUME",
  "INTERNAL_GIT",
  "CUSTOMER_GIT",
  "OBJECT_STORAGE",
] as const;

export class UnsupportedRecoveryProviderError extends Error {
  constructor(readonly requested: string) {
    super(
      `unsupported recovery provider type '${requested}' — supported: ` +
        SUPPORTED_RECOVERY_PROVIDER_TYPES.join(", "),
    );
    this.name = "UnsupportedRecoveryProviderError";
  }
}