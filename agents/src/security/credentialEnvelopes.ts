// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * `MyrmecSecureEnvelopeV1` — SDK open path (design
 * `2026-09-16-credential-envelope-delivery.md` §7/§8/§10).
 *
 * The engine seals session credentials into AES-256-GCM envelopes keyed by a
 * per-run PSK and bound to one sessionId. The SDK derives the session key
 * independently with HKDF-SHA256 (no key material crosses the wire after
 * `host.opened`), re-computes the AAD from the envelope's clear metadata, and
 * decrypts. Byte-compatible with the Java engine side: the ciphertext field
 * carries the GCM tag appended (last 16 bytes), exactly the layout both Java
 * `AES/GCM/NoPadding` and node `createDecipheriv('aes-256-gcm')` use.
 *
 * Cross-language vectors: `credentialVectors.ts` — the single source of truth
 * shared with the engine; both implementations must reproduce them
 * byte-identically.
 */
import { createDecipheriv, createHash, hkdfSync } from "node:crypto";

/** Envelope format name — doubles as the HKDF info string (domain separation). */
export const ENVELOPE_FORMAT = "MyrmecSecureEnvelopeV1";

/** V1 purposes (design §8). */
export type CredentialEnvelopePurpose =
  | "MODEL_PROVIDER"
  | "WORKSPACE_TOKEN";

/** The `MyrmecSecureEnvelopeV1` wire shape (design §8). Base64 for nonce/ciphertext. */
export type MyrmecSecureEnvelope = {
  format: typeof ENVELOPE_FORMAT;
  keyId: string;
  sessionId: string;
  hostId: string;
  purpose: CredentialEnvelopePurpose;
  createdAt: string;
  expiresAt: string;
  plaintextDigest: string;
  nonce: string;
  ciphertext: string;
};

/** Failure codes surfaced by the envelope open path (design §10). */
export type CredentialEnvelopeErrorCode =
  | "TAMPERED"
  | "EXPIRED"
  | "SESSION_MISMATCH"
  | "UNKNOWN_REF"
  | "MISSING_PSK"
  | "DUPLICATE_REF";

/** Thrown for every envelope failure; `.code` classifies the failure. */
export class CredentialEnvelopeError extends Error {
  readonly code: CredentialEnvelopeErrorCode;

  constructor(code: CredentialEnvelopeErrorCode, message: string) {
    super(message);
    this.name = "CredentialEnvelopeError";
    this.code = code;
  }
}

/**
 * Derive the per-session envelope key (design §7):
 *
 *   sessionKey = HKDF-SHA256(ikm=psk, salt=16 raw UUID bytes of sessionId
 *                            (MSB first, dashes stripped, hex-decoded),
 *                            info="MyrmecSecureEnvelopeV1" (ascii), len=32)
 */
export function deriveSessionKey(psk: Uint8Array, sessionId: string): Buffer {
  return Buffer.from(
    hkdfSync("sha256", psk, uuidSalt(sessionId), Buffer.from(ENVELOPE_FORMAT, "ascii"), 32),
  );
}

/** 16 raw UUID bytes, MSB first (java UUID.toString order, no dashes/braces). */
function uuidSalt(sessionId: string): Buffer {
  const hex = sessionId.replace(/-/g, "");
  if (!/^[0-9a-fA-F]{32}$/.test(hex)) {
    throw new CredentialEnvelopeError(
      "SESSION_MISMATCH",
      `sessionId is not a UUID: '${sessionId}'`,
    );
  }
  return Buffer.from(hex, "hex");
}

/**
 * Deterministic AAD byte string (design §8): every field the host needs to
 * re-compute the AAD is carried in the clear — the host never guesses.
 */
export function envelopeAad(envelope: MyrmecSecureEnvelope): Buffer {
  const parts = [
    ENVELOPE_FORMAT,
    envelope.keyId,
    envelope.sessionId,
    envelope.hostId,
    envelope.purpose,
    envelope.expiresAt,
    envelope.plaintextDigest,
  ];
  return Buffer.from(parts.join("|"), "utf8");
}

/**
 * Open one envelope: verify the session binding, AES-256-GCM authentication
 * against the re-computed AAD, the plaintext digest, and expiry; return the
 * plaintext. Any failure throws {@link CredentialEnvelopeError} with a
 * `.code` of `TAMPERED` (tag/AAD/digest mismatch), `EXPIRED`, or
 * `SESSION_MISMATCH` (envelope bound to another session).
 */
export function openEnvelope(
  envelope: MyrmecSecureEnvelope,
  sessionKey: Buffer,
  expectedSessionId: string,
): string {
  if (envelope.format !== ENVELOPE_FORMAT) {
    throw new CredentialEnvelopeError(
      "TAMPERED",
      `Unknown envelope format: '${envelope.format}'`,
    );
  }

  // 1. AES-256-GCM authentication + decryption. The AAD is rebuilt from the
  //    clear metadata — tampering with ANY authenticated field (keyId,
  //    sessionId, hostId, purpose, expiresAt, plaintextDigest) or the
  //    ciphertext breaks the GCM tag here.
  let plaintext: Buffer;
  try {
    const nonce = Buffer.from(envelope.nonce, "base64");
    const sealed = Buffer.from(envelope.ciphertext, "base64");
    if (sealed.length < 16) {
      throw new Error("ciphertext field is shorter than the GCM tag");
    }
    const tag = sealed.subarray(sealed.length - 16);
    const body = sealed.subarray(0, sealed.length - 16);
    const decipher = createDecipheriv("aes-256-gcm", sessionKey, nonce);
    decipher.setAAD(envelopeAad(envelope));
    decipher.setAuthTag(tag);
    plaintext = Buffer.concat([decipher.update(body), decipher.final()]);
  } catch (err) {
    const reason = err instanceof Error ? err.message : String(err);
    throw new CredentialEnvelopeError(
      "TAMPERED",
      `Envelope failed AES-256-GCM authentication: ${reason}`,
    );
  }

  // 2. Plaintext digest — binds the envelope to exactly one plaintext, so a
  //    ciphertext/metadata substitution cannot mint a different secret.
  const plaintextText = plaintext.toString("utf8");
  const digestPrefix = "sha256:";
  const actualDigest = createHash("sha256").update(plaintext).digest("base64");
  if (
    !envelope.plaintextDigest.startsWith(digestPrefix) ||
    envelope.plaintextDigest.slice(digestPrefix.length) !== actualDigest
  ) {
    throw new CredentialEnvelopeError(
      "TAMPERED",
      "Envelope plaintext digest mismatch",
    );
  }

  // 3. Expiry — defense-in-depth against later replay.
  const expiresAtMs = Date.parse(envelope.expiresAt);
  if (!Number.isFinite(expiresAtMs) || Date.now() > expiresAtMs) {
    throw new CredentialEnvelopeError(
      "EXPIRED",
      `Envelope expired at ${envelope.expiresAt}`,
    );
  }

  // 4. Session binding — an envelope is decryptable only for its named
  //    session; a validly sealed envelope delivered into another session's
  //    open fails closed here.
  if (envelope.sessionId !== expectedSessionId) {
    throw new CredentialEnvelopeError(
      "SESSION_MISMATCH",
      `Envelope is bound to session '${envelope.sessionId}', expected '${expectedSessionId}'`,
    );
  }

  return plaintextText;
}