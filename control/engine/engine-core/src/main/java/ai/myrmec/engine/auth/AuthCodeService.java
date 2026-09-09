// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.InvalidTokenException;
import ai.myrmec.engine.user.UserAuthService;
import ai.myrmec.engine.user.dto.LoginResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Desktop-client login flow (VS Code plugin): the hosted login page
 * redeems the logged-in JWT for a one-time code; the plugin exchanges
 * the code for a user token pair on any engine replica. Codes are
 * 256-bit random, stored SHA-256-hashed, single-use via a portable
 * compare-and-swap UPDATE, and short-TTL. The plaintext code exists
 * only in the issue response and the loopback redirect.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthCodeService {

    static final String CODE_PREFIX = "myr_auth_";
    /** Entropy: 32 random bytes, base64url-encoded (~43 chars). */
    private static final int CODE_BYTES = 32;

    private final AuthCodeRepository authCodeRepository;
    private final UserAuthService userAuthService;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Short TTL — the loopback redirect and exchange happen in seconds. */
    @Value("${myrmec.auth.code-ttl-seconds:60}")
    private long codeTtlSeconds;

    /**
     * Issue a one-time code for the logged-in user. {@code redirectUri}
     * must be an http loopback URL (the plugin's local listener) — a
     * non-loopback target would leak the code to a third party.
     */
    @Transactional
    public IssuedAuthCode issue(UUID userId, String redirectUri) {
        String normalized = requireLoopback(redirectUri);

        byte[] entropy = new byte[CODE_BYTES];
        secureRandom.nextBytes(entropy);
        String code = CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        AuthCode row = new AuthCode();
        row.setCodeHash(sha256Hex(code));
        row.setUserId(userId);
        row.setRedirectUri(normalized);
        row.setExpiresAt(Instant.now().plusSeconds(codeTtlSeconds));
        authCodeRepository.save(row);

        log.info("Issued authorization code for user {} (ttl={}s)", userId, codeTtlSeconds);
        // Plaintext returned exactly once, to the caller holding the JWT.
        return new IssuedAuthCode(code, row.getExpiresAt());
    }

    /**
     * Exchange a one-time code for a fresh user token pair. Redemption
     * is a single portable CAS UPDATE — exactly one racing exchange can
     * win; every later attempt sees "invalid code". Fails closed on
     * unknown/expired/used codes.
     */
    @Transactional
    public LoginResponse exchange(String code, String redirectUri) {
        String normalized = requireLoopback(redirectUri);
        String hash = sha256Hex(code);

        AuthCode row = authCodeRepository.findByCodeHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired authorization code"));

        // Bind check first: a code redeemed from the wrong client must
        // never mint tokens. (Nothing is consumed here — a mismatched
        // attempt simply can never pass this check, and consuming in
        // this transaction would roll back with the exception anyway.)
        if (!normalized.equals(row.getRedirectUri())) {
            log.warn("Authorization code redirect mismatch: issued-to != exchanging client");
            throw new InvalidTokenException("Authorization code was issued for a different client");
        }

        // Redeem — the portable CAS: exactly one racing exchange can
        // win; every later attempt fails the used-at predicate.
        int won = authCodeRepository.redeem(hash, Instant.now());
        if (won != 1) {
            log.warn("Authorization code exchange rejected (already used or expired)");
            throw new InvalidTokenException("Invalid or expired authorization code");
        }

        LoginResponse tokens = userAuthService.issueTokensForUser(row.getUserId());
        log.info("Authorization code exchanged for user {}", row.getUserId());
        return tokens;
    }

    /**
     * Only plain http loopback URLs (127.0.0.1 / [::1], any port) are
     * accepted as plugin redirect targets. The plugin owns the port
     * via a short-lived local listener; anything else would hand the
     * one-time code to a third party.
     */
    private String requireLoopback(String redirectUri) {
        URI uri;
        try {
            uri = new URI(redirectUri);
        } catch (URISyntaxException e) {
            throw new BadRequestException("redirectUri must be a valid URI");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean loopbackHost = "127.0.0.1".equals(host) || "[::1]".equals(host) || "localhost".equals(host);
        if (!"http".equalsIgnoreCase(scheme) || host == null || !loopbackHost || uri.getPort() < 0) {
            throw new BadRequestException("redirectUri must be an http loopback URL (127.0.0.1:<port>)");
        }
        return redirectUri.trim();
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Response of {@code POST /auth/authorize-code}: the one-time
     * plaintext code and its expiry. Never logged, never persisted.
     */
    public record IssuedAuthCode(String code, Instant expiresAt) {}
}