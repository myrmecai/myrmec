// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One-time authorization code for the desktop-client login flow (VS
 * Code plugin). The hosted login page redeems the logged-in JWT for a
 * code ({@code POST /auth/authorize-code}); the plugin exchanges the
 * code on any engine replica ({@code POST /auth/code/exchange}) for a
 * fresh user token pair. Codes are stored **hashed** (SHA-256 hex — a
 * database leak yields no usable codes), are single-use via a portable
 * compare-and-swap UPDATE (no DB-specific locking), and expire after a
 * short TTL. {@code codeChallenge}/{@code challengeMethod} are reserved
 * for the future PKCE upgrade.
 */
@Entity
@Table(name = "auth_codes")
@Getter
@Setter
@NoArgsConstructor
public class AuthCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** SHA-256 hex of the plaintext code. The plaintext never persists. */
    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * The loopback URI the plugin registered at sign-in; recorded so a
     * leaked code cannot be redeemed by a different client. Validated
     * to be loopback at issue time.
     */
    @Column(name = "redirect_uri", nullable = false, updatable = false, length = 512)
    private String redirectUri;

    /** Reserved for PKCE ({@code code_challenge}); null until then. */
    @Column(name = "code_challenge", length = 128)
    private String codeChallenge;

    /** Reserved for PKCE (e.g. {@code S256}); null until then. */
    @Column(name = "challenge_method", length = 16)
    private String challengeMethod;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Set by the winning exchange (single-use marker). */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}