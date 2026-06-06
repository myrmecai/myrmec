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
 * </ul>
 */
@Component
public class JwtTokenProvider {

    private static final String TOKEN_TYPE_CLAIM = "type";
    private static final String PRINCIPAL_CLAIM = "principal";
    private static final String NAME_CLAIM = "name";
    private static final String EMAIL_CLAIM = "email";
    private static final String ROLES_CLAIM = "roles";

    private static final String ACCESS_TOKEN = "access";
    private static final String REFRESH_TOKEN = "refresh";

    public static final String PRINCIPAL_AGENT = "AGENT";
    public static final String PRINCIPAL_USER = "USER";

    private final SecretKey secretKey;
    private final long accessTokenMinutes;
    private final long refreshTokenDays;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-minutes:15}") long accessTokenMinutes,
            @Value("${jwt.refresh-token-days:7}") long refreshTokenDays) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenMinutes = accessTokenMinutes;
        this.refreshTokenDays = refreshTokenDays;
    }

    // ==================== Agent tokens ====================

    public String generateAgentAccessToken(UUID agentId, String agentName) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(agentId.toString())
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN)
                .claim(PRINCIPAL_CLAIM, PRINCIPAL_AGENT)
                .claim(NAME_CLAIM, agentName)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public String generateAgentRefreshToken(UUID agentId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(refreshTokenDays, ChronoUnit.DAYS);

        return Jwts.builder()
                .subject(agentId.toString())
                .claim(TOKEN_TYPE_CLAIM, REFRESH_TOKEN)
                .claim(PRINCIPAL_CLAIM, PRINCIPAL_AGENT)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    /**
     * @deprecated Use {@link #generateAgentAccessToken(UUID, String)} instead
     */
    @Deprecated
    public String generateAccessToken(UUID agentId) {
        return generateAgentAccessToken(agentId, null);
    }

    /**
     * @deprecated Use {@link #generateAgentRefreshToken(UUID)} instead
     */
    @Deprecated
    public String generateRefreshToken(UUID agentId) {
        return generateAgentRefreshToken(agentId);
    }

    // ==================== User tokens ====================

    public String generateUserAccessToken(UUID userId, String name, String email, List<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
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

    /**
     * @deprecated Use {@link #getSubjectId(String)} instead
     */
    @Deprecated
    public UUID getAgentId(String token) {
        return getSubjectId(token);
    }

    public String getPrincipalType(String token) {
        String principal = parseToken(token).getPayload().get(PRINCIPAL_CLAIM, String.class);
        // Backwards compatibility: if no principal claim, assume AGENT
        return principal != null ? principal : PRINCIPAL_AGENT;
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

    public boolean isAgentToken(String token) {
        return PRINCIPAL_AGENT.equals(getPrincipalType(token));
    }

    public boolean isUserToken(String token) {
        return PRINCIPAL_USER.equals(getPrincipalType(token));
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
