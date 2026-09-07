// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { createHash } from "node:crypto";
import { RecoveryArtifact } from "./RecoveryArtifact.js";
import { LocalRecoverySnapshotStore } from "./LocalRecoverySnapshotStore.js";
import {
  RESERVED_RECOVERY_PROVIDER_TYPES,
  SUPPORTED_RECOVERY_PROVIDER_TYPES,
} from "./RecoverySnapshotStore.js";

let root: string;

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "recovery-test-"));
});

afterEach(() => {
  rmSync(root, { recursive: true, force: true });
});

const key = {
  keyId: "host-ring-1",
  key: new Uint8Array(32).fill(0xCD),
  runId: "11111111-1111-1111-1111-111111111111",
  generation: 3,
};

const payloadOf = (text: string) => new TextEncoder().encode(text);
const sha = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");

describe("RecoveryArtifact (§17.2)", () => {
  function buildInput() {
    const files = new Map<string, Uint8Array>();
    const moduleBytes = payloadOf("export const answer = 42;\n");
    const binaryBytes = new Uint8Array([0, 1, 2, 250, 251, 252, 253]);
    files.set(sha(moduleBytes), moduleBytes);
    files.set(sha(binaryBytes), binaryBytes);
    return {
      manifest: {
        schemaVersion: "2.0" as const,
        codec: "gzip" as const,
        repositoryIdentity: "https://example.com/repo.git#main",
        sourceBaseCommit: "abc123",
        expectedHead: "abc123",
        candidateTreeHash: "def456",
        workspaceGeneration: 3,
        policyContentDigest: "p".repeat(64),
        operations: [
          { path: "src/module.ts", kind: "ADD" as const, payloadHash: sha(moduleBytes), sizeBytes: moduleBytes.length },
          { path: "assets/logo.bin", kind: "MODIFY" as const, payloadHash: sha(binaryBytes) },
          { path: "old/legacy.ts", kind: "DELETE" as const },
          { path: "src/old.ts", kind: "RENAME" as const, targetPath: "src/new.ts" },
        ],
      },
      files,
      continuationJson: JSON.stringify({
        continuationId: "cont-1",
        continuationRef: "opaque-ref",
        snapshotTreeHash: "def456",
        workspaceRevision: 7,
        stateDigest: "s".repeat(64),
      }),
      localObjectsPack: new Uint8Array([9, 9, 9]),
    };
  }

  test("seal → open round-trips the complete artifact", () => {
    const input = buildInput();
    const sealed = RecoveryArtifact.seal(input, key);

    // Compressed-then-encrypted: the plaintext never appears raw.
    expect(Buffer.from(sealed).includes("export const answer")).toBe(false);

    const parsed = RecoveryArtifact.open(sealed, key);
    expect(parsed.manifest.schemaVersion).toBe("2.0");
    expect(parsed.manifest.codec).toBe("gzip");
    expect(parsed.manifest.operations).toHaveLength(4);
    expect(parsed.files.size).toBe(2);
    expect(new TextDecoder().decode(parsed.files.get(sha(payloadOf("export const answer = 42;\n")))!))
      .toContain("export const answer");
    expect(parsed.files.get(sha(new Uint8Array([0, 1, 2, 250, 251, 252, 253])))).toBeDefined();
    expect(JSON.parse(parsed.continuationJson).continuationId).toBe("cont-1");
    expect(parsed.localObjectsPack).toEqual(new Uint8Array([9, 9, 9]));
  });

  test("a wrong key fails closed (no partial material)", () => {
    const sealed = RecoveryArtifact.seal(buildInput(), key);
    const wrongKey = { ...key, key: new Uint8Array(32).fill(0xEE) };
    expect(() => RecoveryArtifact.open(sealed, wrongKey)).toThrow();
  });

  test("a wrong runId audience fails closed", () => {
    const sealed = RecoveryArtifact.seal(buildInput(), key);
    const wrongRun = { ...key, runId: "99999999-9999-9999-9999-999999999999" };
    expect(() => RecoveryArtifact.open(sealed, wrongRun)).toThrow();
  });

  test("a tampered byte fails closed (GCM tag)", () => {
    const sealed = Buffer.from(RecoveryArtifact.seal(buildInput(), key));
    sealed[sealed.length - 5] ^= 0xFF;
    expect(() => RecoveryArtifact.open(new Uint8Array(sealed), key)).toThrow();
  });

  test("binary payloads survive the tar round-trip byte-for-byte", () => {
    const input = buildInput();
    const sealed = RecoveryArtifact.seal(input, key);
    const parsed = RecoveryArtifact.open(sealed, key);
    const binary = input.files.get(sha(new Uint8Array([0, 1, 2, 250, 251, 252, 253])))!;
    expect(Array.from(parsed.files.get(sha(binary))!)).toEqual(Array.from(binary));
  });
});

describe("LocalRecoverySnapshotStore (§17.2)", () => {
  test("put verifies the digest before publishing; get verifies on read", async () => {
    const store = new LocalRecoverySnapshotStore({ workspaceRoot: root });
    const identity = { runId: key.runId, generation: 3, snapshotId: "snap-1" };
    const bytes = payloadOf("artifact bytes");

    await expect(
      store.put(identity, bytes, "0".repeat(64)),
    ).rejects.toThrow(/digest mismatch/);

    const digest = sha(bytes);
    await store.put(identity, bytes, digest);
    const loaded = await store.get(identity);
    expect(loaded).not.toBeNull();
    expect(loaded!.digest).toBe(digest);
    expect(Array.from(loaded!.bytes)).toEqual(Array.from(bytes));
  });

  test("delete and deleteRun remove artifacts; listRun inventories them", async () => {
    const store = new LocalRecoverySnapshotStore({ workspaceRoot: root });
    const a = { runId: key.runId, generation: 3, snapshotId: "snap-1" };
    const b = { runId: key.runId, generation: 3, snapshotId: "snap-2" };
    const bytes = payloadOf("x");
    await store.put(a, bytes, sha(bytes));
    await store.put(b, bytes, sha(bytes));

    expect(store.listRun(key.runId)).toEqual([{ generation: 3, snapshotIds: ["snap-1", "snap-2"] }]);

    await store.delete(a);
    expect(await store.get(a)).toBeNull();

    await store.deleteRun(key.runId);
    expect(store.listRun(key.runId)).toEqual([]);
  });

  test("the provider contract rejects unsupported types (fail, not fall back)", () => {
    // Reserved provider names must fail configuration validation — never
    // silently fall back to HOST_LOCAL.
    for (const reserved of RESERVED_RECOVERY_PROVIDER_TYPES) {
      expect(SUPPORTED_RECOVERY_PROVIDER_TYPES).not.toContain(reserved);
    }
    expect(SUPPORTED_RECOVERY_PROVIDER_TYPES).toEqual(["HOST_LOCAL"]);
    expect(storeProviderId()).toBe("host-local");
  });

  function storeProviderId(): string {
    const store = new LocalRecoverySnapshotStore({ workspaceRoot: root });
    return store.providerId;
  }
});