// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.auth.dto.AuthorizeCodeResponse;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.user.dto.LoginResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Desktop-client login contract (VS Code plugin):
 * {@code POST /auth/authorize-code} (USER JWT → one-time code) and
 * {@code POST /auth/code/exchange} (code → token pair).
 *
 * <p>Covers the properties the plugin depends on: round-trip; the
 * one-time-use CAS (a second exchange fails); TTL expiry; loopback-only
 * redirect URIs; client binding (a different loopback client cannot
 * redeem a code); unauthenticated authorize; and the sweeper purge.</p>
 */
@DisplayName("Auth codes — desktop client login flow")
class AuthCodeControllerTest extends IntegrationTestBase {

    private static final String LOOPBACK_A = "http://127.0.0.1:49152/callback";
    private static final String LOOPBACK_B = "http://127.0.0.1:49153/callback";

    @Autowired
    private AuthCodeRepository authCodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AuthCodeSweeper authCodeSweeper;

    private UUID createUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setName("Auth Code Tester");
        user.setPasswordHash("$2a$10$dummy");
        user.setProviderCode(ai.myrmec.engine.user.AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }

    /** USER-JWT headers for the given user (the hosted login page's state). */
    private HttpHeaders userJwtHeaders(UUID userId) {
        String token = jwtTokenProvider.generateUserAccessToken(
                userId, "Auth Code Tester", "tester@e2e-test.local", List.of());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    private ResponseEntity<AuthorizeCodeResponse> authorize(UUID userId, String redirectUri) {
        return restTemplate.exchange(
                "/api/v1/auth/authorize-code",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("redirectUri", redirectUri), userJwtHeaders(userId)),
                AuthorizeCodeResponse.class);
    }

    private ResponseEntity<LoginResponse> exchange(String code, String redirectUri) {
        return restTemplate.exchange(
                "/api/v1/auth/code/exchange",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", code, "redirectUri", redirectUri), jsonHeaders()),
                LoginResponse.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        return headers;
    }

    @Test
    @DisplayName("round-trip: authorize with USER JWT → exchange → token pair")
    void roundTripIssuesTokens() {
        UUID userId = createUser("authcode-roundtrip@e2e-test.local");

        ResponseEntity<AuthorizeCodeResponse> issued = authorize(userId, LOOPBACK_A);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        String code = issued.getBody().getCode();
        assertThat(code).startsWith("myr_auth_");
        assertThat(issued.getBody().getExpiresAt()).isAfter(Instant.now());

        ResponseEntity<LoginResponse> exchanged = exchange(code, LOOPBACK_A);
        assertThat(exchanged.getStatusCode()).isEqualTo(HttpStatus.OK);
        LoginResponse tokens = exchanged.getBody();
        assertThat(tokens.getAccessToken()).isNotBlank();
        assertThat(tokens.getRefreshToken()).isNotBlank();
        assertThat(tokens.getUserId()).isEqualTo(userId);
        assertThat(tokens.getRoles()).isNotNull();

        // The plaintext code never persisted — only its hash.
        assertThat(authCodeRepository.findByCodeHash(code)).isEmpty();
    }

    @Test
    @DisplayName("one-time use: a second exchange of the same code is rejected")
    void codeIsSingleUse() {
        UUID userId = createUser("authcode-single-use@e2e-test.local");
        String code = authorize(userId, LOOPBACK_A).getBody().getCode();

        assertThat(exchange(code, LOOPBACK_A).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<LoginResponse> second = exchange(code, LOOPBACK_A);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("expired codes are rejected at exchange (lazy TTL check)")
    void expiredCodeRejected() {
        UUID userId = createUser("authcode-expired@e2e-test.local");
        String code = authorize(userId, LOOPBACK_A).getBody().getCode();

        // Age the row past its TTL directly (expires_at is mapped
        // updatable=false — the bulk update bypasses it).
        authCodeRepository.updateExpiresAt(hashOf(code), Instant.now().minusSeconds(1));

        ResponseEntity<LoginResponse> rejected = exchange(code, LOOPBACK_A);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("redirectUri must be an http loopback URL at issue time")
    void nonLoopbackRedirectRejectedAtIssue() {
        UUID userId = createUser("authcode-nonloopback@e2e-test.local");

        ResponseEntity<String> rejected = restTemplate.exchange(
                "/api/v1/auth/authorize-code",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("redirectUri", "https://evil.example.com/cb"), userJwtHeaders(userId)),
                String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // https even on loopback is refused (the listener is plain http).
        ResponseEntity<String> httpsLoopback = restTemplate.exchange(
                "/api/v1/auth/authorize-code",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("redirectUri", "https://127.0.0.1:49152/callback"), userJwtHeaders(userId)),
                String.class);
        assertThat(httpsLoopback.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("client binding: a code redeemed from a different loopback client is rejected")
    void codeIsBoundToItsClient() {
        UUID userId = createUser("authcode-bound@e2e-test.local");
        String code = authorize(userId, LOOPBACK_A).getBody().getCode();

        ResponseEntity<LoginResponse> stolen = exchange(code, LOOPBACK_B);
        assertThat(stolen.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The rightful client can still redeem it.
        assertThat(exchange(code, LOOPBACK_A).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("authorize-code requires an authenticated principal")
    void authorizeRequiresAuth() {
        ResponseEntity<String> anonymous = restTemplate.exchange(
                "/api/v1/auth/authorize-code",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("redirectUri", LOOPBACK_A), jsonHeaders()),
                String.class);
        // No 401 entry point on /api/v1/** — unauthenticated lands on 403.
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("sweeper purges expired rows and leaves live ones alone")
    void sweeperPurgesOnlyExpired() {
        UUID userId = createUser("authcode-sweeper@e2e-test.local");
        String code = authorize(userId, LOOPBACK_A).getBody().getCode();

        long before = authCodeRepository.count();
        int purged = authCodeSweeper.purgeExpired(Instant.now());
        assertThat(purged).isZero();
        assertThat(authCodeRepository.count()).isEqualTo(before);

        // Age it past TTL, sweep again — the row is gone.
        authCodeRepository.updateExpiresAt(hashOf(code), Instant.now().minusSeconds(1));
        purged = authCodeSweeper.purgeExpired(Instant.now());
        assertThat(purged).isEqualTo(1);
        assertThat(authCodeRepository.findByCodeHash(hashOf(code))).isEmpty();
    }

    /** SHA-256 hex of a code, mirroring the service's hashing. */
    private String hashOf(String code) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}