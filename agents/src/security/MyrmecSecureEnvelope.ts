// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * {@code MyrmecSecureEnvelopeV1} (design §17.2) — the TypeScript mirror of
 * the engine's {@code ai.myrmec.engine.workflow.MyrmecSecureEnvelope}.
 *
 * Layout: four ASCII bytes "MSE1", a four-byte big-endian JSON-header
 * length, the canonical UTF-8 header (authenticated as AAD), AES-256-GCM
 * ciphertext, and the 16-byte authentication tag. Decryption validates the
 * AAD, tag, audience, expiry, and plaintext digest before exposing bytes.
 *
 * The canonical header JSON is: keys sorted lexicographically, string or
 * null values, no whitespace — identical bytes to the Java side.
 */
import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes } from "node:crypto";

export type EnvelopePurpose = "SESSION_CREDENTIALS" | "RECOVERY_SNAPSHOT";

export const ENVELOPE_MAGIC = 0x4d534531; // "MSE1"
export const ENVELOPE_ALGORITHM = "A256GCM";
export const SESSION_CREDENTIAL_INFO = "myrmec/session-credential/v1";

export interface EnvelopeAudience {
  [key: string]: string;
}

export interface EnvelopeOptions {
  keyId: string;
  sessionKey: Uint8Array; // 32 bytes, already derived
  purpose: EnvelopePurpose;
  audience?: EnvelopeAudience;
}

export class MyrmecSecureEnvelope {
  private readonly key: Buffer;
  private readonly options: EnvelopeOptions;

  constructor(options: EnvelopeOptions) {
    if (options.sessionKey.length !== 32) {
      throw new Error("session key must be 32 bytes");
    }
    this.options = options;
    this.key = Buffer.from(options.sessionKey);
  }

  /** Build directly from an already-derived 32-byte session key. */
  static forSessionKey(
    keyId: string,
    sessionKey: Uint8Array,
    purpose: EnvelopePurpose,
    audience?: EnvelopeAudience,
  ): MyrmecSecureEnvelope {
    return new MyrmecSecureEnvelope({ keyId, sessionKey, purpose, audience });
  }

  /**
   * Derive sessionKey = HKDF-SHA256(PSK, sessionId) (§16.1) and build the
   * envelope. Both sides derive the same key independently; no key material
   * crosses the wire after registration.
   */
  static forPsk(
    keyId: string,
    psk: Uint8Array,
    sessionId: string,
    purpose: EnvelopePurpose,
    audience?: EnvelopeAudience,
  ): MyrmecSecureEnvelope {
    const sessionKey = hkdfSha256(
      psk,
      Buffer.from(sessionId, "utf-8"),
      Buffer.from(SESSION_CREDENTIAL_INFO, "utf-8"),
      32,
    );
    return new MyrmecSecureEnvelope({ keyId, sessionKey, purpose, audience });
  }

  /** Seal plaintext into the binary envelope. The header is AAD. */
  seal(plaintext: Uint8Array, expiresAt?: Date): Buffer {
    const nonce = randomBytes(12);
    const header: Record<string, string | null> = {
      version: "1.0",
      purpose: this.options.purpose,
      algorithm: ENVELOPE_ALGORITHM,
      keyId: this.options.keyId,
      nonce: nonce.toString("base64"),
      plaintextSha256: sha256Hex(Buffer.from(plaintext)),
      createdAt: new Date().toISOString(),
      ...(this.options.audience ?? {}),
    };
    if (expiresAt) {
      header.expiresAt = expiresAt.toISOString();
    }

    const headerJson = canonicalJson(header);
    const cipher = createCipheriv("aes-256-gcm", this.key, nonce);
    cipher.setAAD(headerJson);
    const ciphertext = Buffer.concat([cipher.update(Buffer.from(plaintext)), cipher.final()]);
    const tag = cipher.getAuthTag();

    const headerLength = Buffer.alloc(4);
    headerLength.writeUInt32BE(headerJson.length, 0);
    const magic = Buffer.alloc(4);
    magic.writeUInt32BE(ENVELOPE_MAGIC, 0);

    return Buffer.concat([magic, headerLength, headerJson, ciphertext, tag]);
  }

  /**
   * Open an envelope: validate magic, AAD (header), tag, audience, expiry,
   * and plaintext digest before returning bytes. Any failure throws.
   */
  open(envelope: Uint8Array, now: Date = new Date()): Buffer {
    const buf = Buffer.from(envelope);
    if (buf.length < 8) throw new Error("envelope truncated");
    if (buf.readUInt32BE(0) !== ENVELOPE_MAGIC) throw new Error("bad magic");
    const headerLength = buf.readUInt32BE(4);
    if (headerLength <= 0 || 8 + headerLength > buf.length) {
      throw new Error("bad header length");
    }
    const headerJson = buf.subarray(8, 8 + headerLength);
    const ciphertext = buf.subarray(8 + headerLength, buf.length - 16);
    const tag = buf.subarray(buf.length - 16);

    const header = parseHeader(headerJson);

    // Audience / purpose / algorithm / keyId binding.
    requireValue(header, "purpose", this.options.purpose);
    requireValue(header, "algorithm", ENVELOPE_ALGORITHM);
    requireValue(header, "keyId", this.options.keyId);
    for (const [key, value] of Object.entries(this.options.audience ?? {})) {
      requireValue(header, key, value);
    }

    // Expiry.
    const expiresAt = header.get("expiresAt");
    if (expiresAt && now.getTime() > Date.parse(expiresAt)) {
      throw new Error("expired");
    }

    const nonce = Buffer.from(header.get("nonce") ?? "", "base64");
    const decipher = createDecipheriv("aes-256-gcm", this.key, nonce);
    decipher.setAAD(headerJson);
    decipher.setAuthTag(tag);
    const plaintext = Buffer.concat([decipher.update(ciphertext), decipher.final()]);

    // Plaintext digest.
    const digest = header.get("plaintextSha256");
    if (!digest || digest !== sha256Hex(plaintext)) {
      throw new Error("plaintext digest mismatch");
    }
    return plaintext;
  }
}

function requireValue(header: Map<string, string>, key: string, expected: string): void {
  if (header.get(key) !== expected) {
    throw new Error(`header mismatch: ${key}`);
  }
}

/** Canonical JSON: keys sorted lexicographically, string/null values,
 * minimal separators — byte-identical to the Java canonicalJson. */
function canonicalJson(header: Record<string, string | null>): Buffer {
  const keys = Object.keys(header).sort();
  const parts: string[] = [];
  for (const key of keys) {
    const value = header[key];
    parts.push(`${JSON.stringify(key)}:${value === null ? "null" : JSON.stringify(value)}`);
  }
  return Buffer.from(`{${parts.join(",")}}`, "utf-8");
}

function parseHeader(json: Buffer): Map<string, string> {
  const parsed = JSON.parse(json.toString("utf-8")) as Record<string, unknown>;
  const out = new Map<string, string>();
  for (const [key, value] of Object.entries(parsed)) {
    if (value !== null && value !== undefined) {
      out.set(key, String(value));
    }
  }
  return out;
}

function sha256Hex(bytes: Buffer): string {
  return createHash("sha256").update(bytes).digest("hex");
}

/** Minimal HKDF-SHA256 (RFC 5869) — mirrors the Java Hkdf.derive.
 * Extract: PRK = HMAC-SHA256(salt, IKM); Expand: T(i) = HMAC-SHA256(PRK, T(i-1) || info || i). */
export function hkdfSha256(ikm: Uint8Array, salt: Uint8Array, info: Uint8Array, length: number): Buffer {
  const prk = createHmac("sha256", Buffer.from(salt)).update(Buffer.from(ikm)).digest();
  const infoBuf = Buffer.from(info);
  const blocks: Buffer[] = [];
  let t = Buffer.alloc(0);
  let counter = 1;
  let produced = 0;
  while (produced < length) {
    const hmac = createHmac("sha256", prk);
    hmac.update(t);
    hmac.update(infoBuf);
    hmac.update(Buffer.from([counter++]));
    const block = hmac.digest();
    blocks.push(block);
    produced += block.length;
    t = block;
  }
  return Buffer.concat(blocks).subarray(0, length);
}