package ai.myrmec.engine._system.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT token provider supporting access and refresh tokens for both agents and users.
 * 
 * <p>Token types:
 * <ul>
 *   <li>Access token: Short-lived (15 min default), used for API calls</li>
 *   <li>Refresh token: Long-lived (7 days default), used to get new access tokens</li>
 * </ul>
 * 
 * <p>Principal types:
 * <ul>
 *   <li>AGENT: For agent authentication</li>
 *   <li>USER: For human user authentication</li>
 *   <li>AGENT_HOST: For durable agent host (machine) authentication</li>
 * </ul>
 */
@Component
public class JwtTokenProvider {

    private static final String TOKEN_TYPE_CLAIM = "type";
    private static final String PRINCIPAL_CLAIM = "principal";
    private static final String NAME_CLAIM = "name";
    private static final String EMAIL_CLAIM = "email";
    private static final String ROLES_CLAIM = "roles";
    private static final String JTI_CLAIM = "jti";
    private static final String OWNER_USER_ID_CLAIM = "ownerUserId";
    private static final String ISSUER_CLAIM = "iss";

    private static final String ACCESS_TOKEN = "access";
    private static final String REFRESH_TOKEN = "refresh";

    public static final String PRINCIPAL_USER = "USER";
    public static final String PRINCIPAL_AGENT_HOST = "AGENT_HOST";

    private final SecretKey secretKey;
    private final long accessTokenMinutes;
    private final long refreshTokenDays;
    private final String issuer;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-minutes:15}") long accessTokenMinutes,
            @Value("${jwt.refresh-token-days:7}") long refreshTokenDays,
            @Value("${myrmec.auth.issuer:https://myrmec.ai/engine}") String issuer) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenMinutes = accessTokenMinutes;
        this.refreshTokenDays = refreshTokenDays;
        this.issuer = issuer;
    }

    // ==================== Agent Host tokens (protocol §4.1) ====================

    /**
     * Access token for a durable Agent Host principal (unified protocol §4.1).
     * Subject is agent_hosts.id — NOT a runtime agents.id. The host-control
     * WebSocket handshake validates this principal; hostType/ownership are
     * read from the durable host record, never from claims.
     *
     * <p>LOCAL hosts additionally carry the {@code ownerUserId} claim: the
     * registering user the host is bound to. The engine stamps
     * agent_host_instances.owner_user_id from THIS claim at host.open —
     * owner identity is token-verified, never payload-asserted. The claim
     * is omitted for MANAGED hosts (a headless supervisor has no owner).
     */
    public String generateHostAccessToken(UUID agentHostId, String hostName, UUID ownerUserId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .issuer(issuer)
                .subject(agentHostId.toString())
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN)
                .claim(PRINCIPAL_CLAIM, PRINCIPAL_AGENT_HOST)
                .claim(NAME_CLAIM, hostName)
                .claim(OWNER_USER_ID_CLAIM, ownerUserId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Refresh token for a durable Agent Host principal. Same subject and
     * principal; tokenType=refresh. Rotated on use by HostRefreshTokenStore;
     * reuse revokes the family.
     */
    public String generateHostRefreshToken(UUID agentHostId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(refreshTokenDays, ChronoUnit.DAYS);

        return Jwts.builder()
                .issuer(issuer)
                .subject(agentHostId.toString())
                .claim(TOKEN_TYPE_CLAIM, REFRESH_TOKEN)
                .claim(PRINCIPAL_CLAIM, PRINCIPAL_AGENT_HOST)
                .claim(JTI_CLAIM, UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    // ==================== User tokens ====================

    public String generateUserAccessToken(UUID userId, String name, String email, List<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN)
                .claim(PRINCIPAL_CLAIM, PRINCIPAL_USER)
                .claim(NAME_CLAIM, name)
                .claim(EMAIL_CLAIM, email)
                .claim(ROLES_CLAIM, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public String generateUserRefreshToken(UUID userId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(refreshTokenDays, ChronoUnit.DAYS);

        return Jwts.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claim(TOKEN_TYPE_CLAIM, REFRESH_TOKEN)
                .claim(PRINCIPAL_CLAIM, PRINCIPAL_USER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    // ==================== Token parsing ====================

    public Instant getExpiration(String token) {
        return parseToken(token).getPayload().getExpiration().toInstant();
    }

    public UUID getSubjectId(String token) {
        String subject = parseToken(token).getPayload().getSubject();
        return UUID.fromString(subject);
    }

    public String getPrincipalType(String token) {
        return parseToken(token).getPayload().get(PRINCIPAL_CLAIM, String.class);
    }

    public String getName(String token) {
        return parseToken(token).getPayload().get(NAME_CLAIM, String.class);
    }

    public String getEmail(String token) {
        return parseToken(token).getPayload().get(EMAIL_CLAIM, String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        return parseToken(token).getPayload().get(ROLES_CLAIM, List.class);
    }

    /**
     * The ownerUserId claim of a HOST access token (LOCAL hosts): the user
     * the host is bound to. Null when absent (MANAGED hosts, or any other
     * principal — the claim is host-specific).
     */
    public UUID getOwnerUserId(String token) {
        String claim = parseToken(token).getPayload().get(OWNER_USER_ID_CLAIM, String.class);
        return claim != null ? UUID.fromString(claim) : null;
    }

    /** The issuer stamped into every minted token (myrmec.auth.issuer). */
    public String getIssuer() {
        return issuer;
    }

    public boolean isUserToken(String token) {
        return PRINCIPAL_USER.equals(getPrincipalType(token));
    }

    /**
     * True iff the token is an AGENT_HOST-principal token (either type).
     * The subject is a durable agent_hosts.id, never an agents.id.
     */
    public boolean isAgentHostToken(String token) {
        return PRINCIPAL_AGENT_HOST.equals(getPrincipalType(token));
    }

    public boolean isAccessToken(String token) {
        return ACCESS_TOKEN.equals(getTokenType(token));
    }

    public boolean isRefreshToken(String token) {
        return REFRESH_TOKEN.equals(getTokenType(token));
    }

    private String getTokenType(String token) {
        return parseToken(token).getPayload().get(TOKEN_TYPE_CLAIM, String.class);
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean validateAccessToken(String token) {
        return validateToken(token) && isAccessToken(token);
    }

    public boolean validateRefreshToken(String token) {
        return validateToken(token) && isRefreshToken(token);
    }

    private Jws<Claims> parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token);
    }
}
