// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
/**
 * Cross-language test vectors for MyrmecSecureEnvelopeV1.
 *
 * These vectors are the SINGLE SOURCE OF TRUTH shared by the Java engine and
 * the TypeScript SDK. Both implementations MUST pass them byte-identically.
 * Any change to the envelope format bumps the format name and regenerates.
 *
 * Construction (design doc: 2026-09-16-credential-envelope-delivery.md §7/§8):
 *   sessionKey = HKDF-SHA256(ikm=psk, salt=sessionIdRawBytes, info="MyrmecSecureEnvelopeV1", len=32)
 *   salt        = 16 raw UUID bytes, MSB first (java UUID.toString order, no dashes/braces)
 *   AAD         = "MyrmecSecureEnvelopeV1|" + keyId + "|" + sessionId + "|" + hostId + "|"
 *                 + purpose + "|" + expiresAt + "|" + plaintextDigest
 *   plaintextDigest = "sha256:" + base64(sha256(plaintextUtf8Bytes))
 *   envelope    = AES-256-GCM(key=sessionKey, nonce=nonce, plaintext, aad)
 *                 ciphertext field = base64(ciphertext || 16-byte tag)   [tag appended]
 *   nonce       = 12 bytes, random per envelope (fixed here for reproducibility)
 *
 * All byte values below are base64 (standard, padded). All ids are lowercase
 * UUID strings without braces.
 */
export const HKDF_INFO = "MyrmecSecureEnvelopeV1";

/** Deterministic fixture — never a real key. */
export const VECTOR_1 = {
  pskBase64: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh4=",
  pskKeyId: "0f0e0d0c-0b0a-4901-8203-040506070809",
  sessionId: "12345678-90ab-4cde-9f01-234567890abc",
  hostId: "fedcba98-7654-4321-0fed-cba987654321",
  purpose: "MODEL_PROVIDER",
  expiresAt: "2026-09-17T10:15:30.000Z",
  plaintext: "sk-test-provider-key-0123456789",

  // 12-byte nonce (fixed for the vector)
  nonceBase64: "AAAAAAAAAAAAAAAAAAAAAA==",

  // EXPECTED — both implementations must produce these exact bytes
  // (generated with node:crypto reference run 2026-09-16).
  sessionKeyHex: "8edc74d2b2d914fc2028390a7f181786865fb9c8acac5f5e498c911cc6307848",
  ciphertextBase64: "b37AH5DB8Xt+cCmuKC2vt7HJOBaYtGh5aU4fB4qefUdf2jNACLn/U+a5JVh1Ix0=",
} as const;

export const VECTOR_2_KEYLESS = {
  // documents the no-credentials case: session.open with keyless local model
  // omits model.credentialRef and the credentials array entirely.
  note: "no envelope; schema-level case only",
} as const;