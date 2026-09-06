// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * RecoveryArtifact (design §17.2, Feature 10): the versioned
 * compressed-then-encrypted container for a complete materialized delta
 * relative to `expectedHead` — it never depends on an older snapshot.
 *
 * Container layout (exact §17.2):
 *   manifest.json         — schema version, repository identity, sourceBaseCommit,
 *                           expectedHead, candidate-tree hash, workspace
 *                           generation, policy-content digest, payload hashes,
 *                           file operations (additions, modifications,
 *                           deletions, renames, modes, symlink targets)
 *   files/                — raw materialized payloads (text and binary alike)
 *   continuation.json     — the runner continuation record
 *   local-objects.pack    — optional missing Myrmec-created Git objects for
 *                           unpushed checkpoint ancestry (never a full bundle)
 *
 * V1 codec: gzip (both Java and Node stdlibs; the design targets zstd — the
 * versioned manifest carries the codec so the migration is mechanical and
 * artifacts self-describe). Compression occurs BEFORE encryption; the outer
 * bytes are sealed with MyrmecSecureEnvelopeV1 purpose RECOVERY_SNAPSHOT.
 */
import { gzipSync, gunzipSync } from "node:zlib";
import { MyrmecSecureEnvelope } from "../security/MyrmecSecureEnvelope.js";

/** One materialized file operation relative to expectedHead. */
export interface FileOperation {
  /** Repository-relative path. Never absolute, never escaping. */
  path: string;
  kind: "ADD" | "MODIFY" | "DELETE" | "RENAME";
  /** Target path for RENAME. */
  targetPath?: string;
  /** POSIX-style mode bits (permissions only). */
  mode?: number;
  /** Symlink target when the entry is a symlink. */
  symlinkTarget?: string;
  /** Present for ADD/MODIFY: the payload lives in files/<hash>. */
  payloadHash?: string;
  /** Byte length of the payload (pre-compression). */
  sizeBytes?: number;
}

export interface RecoveryManifest {
  schemaVersion: "2.0";
  codec: "gzip";
  repositoryIdentity: string;
  sourceBaseCommit: string;
  expectedHead: string;
  candidateTreeHash: string;
  workspaceGeneration: number;
  /** Host-computed SHA-256 over the canonical ExecutionPolicy block. */
  policyContentDigest: string;
  operations: FileOperation[];
}

export interface BuildArtifactInput {
  manifest: RecoveryManifest;
  /** Payloads keyed by payloadHash (raw bytes). */
  files: Map<string, Uint8Array>;
  /** The runner continuation record (serialized verbatim). */
  continuationJson: string;
  /** Optional missing Git objects for unpushed checkpoint ancestry. */
  localObjectsPack?: Uint8Array;
}

export interface ParsedArtifact {
  manifest: RecoveryManifest;
  files: Map<string, Uint8Array>;
  continuationJson: string;
  localObjectsPack?: Uint8Array;
}

/** A 32-byte recovery key (from the persistent Host keyring, §17.2). */
export interface RecoveryKeyContext {
  keyId: string;
  /** The 32-byte key itself — from the administrator-configured Host keyring. */
  key: Uint8Array;
  /** Audience binding: the run this artifact belongs to. */
  runId: string;
  generation: number;
}

// ── minimal ustar tar ───────────────────────────────────────────────

const BLOCK = 512;

function tarWrite(entries: { name: string; data: Uint8Array; mode?: number; symlinkTarget?: string }[]): Uint8Array {
  const chunks: Buffer[] = [];
  for (const entry of entries) {
    const header = Buffer.alloc(BLOCK);
    writeTarString(header, 0, 100, entry.name);
    writeTarString(header, 100, 8, (entry.mode ?? 0o644).toString(8).padStart(6, "0") + "\0");
    writeTarString(header, 108, 8, "00000000\0"); // uid
    writeTarString(header, 116, 8, "00000000\0"); // gid
    writeTarString(header, 124, 12, entry.data.length.toString(8).padStart(11, "0") + "\0");
    writeTarString(header, 136, 12, "00000000000\0"); // mtime — deterministic artifacts
    if (entry.symlinkTarget) {
      writeTarString(header, 148, 8, checksumOf(header));
      writeTarString(header, 156, 1, "2"); // symlink
      writeTarString(header, 157, 100, entry.symlinkTarget);
    } else {
      writeTarString(header, 148, 8, checksumOf(header));
      writeTarString(header, 156, 1, "0"); // regular
    }
    writeTarString(header, 257, 6, "ustar\0");
    writeTarString(header, 263, 2, "00");
    chunks.push(header, Buffer.from(entry.data));
    const pad = (BLOCK - (entry.data.length % BLOCK)) % BLOCK;
    if (pad > 0) chunks.push(Buffer.alloc(pad));
  }
  chunks.push(Buffer.alloc(BLOCK * 2)); // end-of-archive
  return new Uint8Array(Buffer.concat(chunks));
}

function tarRead(archive: Uint8Array): { name: string; data: Uint8Array; symlinkTarget?: string }[] {
  const buf = Buffer.from(archive);
  const entries: { name: string; data: Uint8Array; symlinkTarget?: string }[] = [];
  let offset = 0;
  while (offset + BLOCK <= buf.length) {
    const header = buf.subarray(offset, offset + BLOCK);
    if (header.subarray(0, 100).every((b) => b === 0)) break; // end
    const name = readTarString(header, 0, 100);
    // readTarString stops at the first NUL, so the size field is pure octal
    // digits — any trailing pad spaces are ignored by parseInt.
    const size = Number.parseInt(readTarString(header, 124, 12).trim(), 8) || 0;
    const typeFlag = String.fromCharCode(header[156] ?? 48);
    const symlinkTarget =
      typeFlag === "2" ? readTarString(header, 157, 100) : undefined;
    const data = buf.subarray(offset + BLOCK, offset + BLOCK + size);
    entries.push({ name, data: new Uint8Array(data), symlinkTarget });
    offset += BLOCK + size + ((BLOCK - (size % BLOCK)) % BLOCK);
  }
  return entries;
}

function writeTarString(buf: Buffer, offset: number, length: number, value: string): void {
  const bytes = Buffer.from(value, "utf-8");
  buf.fill(0, offset, offset + length);
  bytes.subarray(0, length).copy(buf, offset);
}

function readTarString(buf: Buffer, offset: number, length: number): string {
  const slice = buf.subarray(offset, offset + length);
  const end = slice.indexOf(0);
  return slice.subarray(0, end === -1 ? length : end).toString("utf-8");
}

function checksumOf(header: Buffer): string {
  // The checksum field itself counts as spaces.
  const probe = Buffer.from(header);
  probe.fill(0x20, 148, 156);
  let sum = 0;
  for (const byte of probe) sum += byte;
  return sum.toString(8).padStart(6, "0") + "\0 ";
}

// ── the artifact ────────────────────────────────────────────────────

export class RecoveryArtifact {
  /**
   * Build the sealed artifact bytes: tar (ustar) → compress (gzip) →
   * encrypt (MyrmecSecureEnvelopeV1, purpose RECOVERY_SNAPSHOT, audience
   * runId + generation).
   */
  static seal(input: BuildArtifactInput, key: RecoveryKeyContext): Uint8Array {
    const entries: { name: string; data: Uint8Array; mode?: number }[] = [
      {
        name: "manifest.json",
        data: new TextEncoder().encode(JSON.stringify(input.manifest, null, 2)),
      },
      {
        name: "continuation.json",
        data: new TextEncoder().encode(input.continuationJson),
      },
    ];
    for (const [hash, payload] of input.files) {
      entries.push({ name: `files/${hash}`, data: payload, mode: 0o644 });
    }
    if (input.localObjectsPack) {
      entries.push({ name: "local-objects.pack", data: input.localObjectsPack });
    }
    const tar = tarWrite(entries);
    const compressed = gzipSync(tar);

    const envelope = MyrmecSecureEnvelope.forSessionKey(
      key.keyId,
      key.key,
      "RECOVERY_SNAPSHOT",
      { runId: key.runId, generation: String(key.generation) },
    );
    const sealed = envelope.seal(compressed);
    return new Uint8Array(sealed);
  }

  /**
   * Open + parse a sealed artifact. Every failure is terminal — no partial
   * resume (§17.2: an invalid artifact returns RECOVERY_SNAPSHOT_INVALID).
   */
  static open(sealed: Uint8Array, key: RecoveryKeyContext): ParsedArtifact {
    const envelope = MyrmecSecureEnvelope.forSessionKey(
      key.keyId,
      key.key,
      "RECOVERY_SNAPSHOT",
      { runId: key.runId, generation: String(key.generation) },
    );
    const compressed = envelope.open(sealed, new Date());
    const tar = gunzipSync(Buffer.from(compressed));
    const entries = tarRead(new Uint8Array(tar));

    const manifestEntry = entries.find((e) => e.name === "manifest.json");
    const continuationEntry = entries.find((e) => e.name === "continuation.json");
    if (!manifestEntry || !continuationEntry) {
      throw new Error("recovery artifact missing manifest.json or continuation.json");
    }
    const manifest = JSON.parse(
      new TextDecoder().decode(manifestEntry.data),
    ) as RecoveryManifest;
    if (manifest.schemaVersion !== "2.0" || manifest.codec !== "gzip") {
      throw new Error(`unsupported recovery artifact: ${manifest.schemaVersion}/${manifest.codec}`);
    }
    // Audience binding already enforced by the envelope (runId, generation).

    const files = new Map<string, Uint8Array>();
    let localObjectsPack: Uint8Array | undefined;
    for (const entry of entries) {
      if (entry.name.startsWith("files/")) {
        files.set(entry.name.slice("files/".length), entry.data);
      } else if (entry.name === "local-objects.pack") {
        localObjectsPack = entry.data;
      }
    }
    return {
      manifest,
      files,
      continuationJson: new TextDecoder().decode(continuationEntry.data),
      localObjectsPack,
    };
  }
}