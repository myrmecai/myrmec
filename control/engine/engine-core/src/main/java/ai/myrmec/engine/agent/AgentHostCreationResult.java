// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.agent;

/**
 * Result of agent-host creation, containing the {@link AgentHost}, its
 * plaintext registration key, and — Feature 10 (§16.1/§17.2) — the
 * session-credential PSK (base64) with its keyId. The registration key and
 * the PSK are only available at creation time and never stored/returned
 * again; only the EncryptionService-encrypted PSK copy persists.
 */
public record AgentHostCreationResult(
        AgentHost agent,
        String registrationKey,
        String pskKeyId,
        String pskBase64) {
}