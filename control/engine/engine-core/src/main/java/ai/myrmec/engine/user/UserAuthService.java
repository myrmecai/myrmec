package ai.myrmec.engine.user;

import ai.myrmec.engine._system.crypto.EncryptionService;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.InvalidTokenException;
import ai.myrmec.engine._system.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.myrmec.engine.user.dto.LoginRequest;
import ai.myrmec.engine.user.dto.LoginResponse;
import ai.myrmec.engine.user.dto.ExternalAuthStartResponse;
import ai.myrmec.engine.user.dto.UserRefreshRequest;
import ai.myrmec.engine.user.dto.UserRefreshResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for user authentication (login, token refresh).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAuthService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final EncryptionService encryptionService;
    private final AuthProviderService authProviderService;
    private final ExternalAuthStateService externalAuthStateService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Authenticate user with email and password.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        User user = userRepository.findByEmail(email)
                .orElse(null);

        if (user == null) {
            log.warn("Login failed: user not found for email: {}", email);
            throw new BadRequestException("Invalid email or password");
        }

        if (!user.getIsActive()) {
            log.warn("Login failed: inactive user: {}", email);
            throw new BadRequestException("User account is disabled");
        }

        if (!AuthenticationProvider.LOCAL_CODE.equals(user.getProviderCode())) {
            log.warn("Login failed: non-LOCAL user attempted password login: {}", email);
            throw new BadRequestException("This account uses external authentication");
        }

        authProviderService.validateProviderEnabledForUser(user.getProviderCode());

        if (!userService.verifyPassword(user, request.getPassword())) {
            log.warn("Login failed: invalid password for email: {}", email);
            throw new BadRequestException("Invalid email or password");
        }

        List<String> roles = buildRolesClaim(user.getId());

        String accessToken = jwtTokenProvider.generateUserAccessToken(
                user.getId(), user.getName(), user.getEmail(), roles);
        String refreshToken = jwtTokenProvider.generateUserRefreshToken(user.getId());

        log.info("User logged in: {} (provider: {})", email, user.getProviderCode());

        return LoginResponse.builder()
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .roles(roles)
                .accessToken(accessToken)
                .accessTokenExpiresAt(jwtTokenProvider.getExpiration(accessToken))
                .refreshToken(refreshToken)
                .refreshTokenExpiresAt(jwtTokenProvider.getExpiration(refreshToken))
                .build();
    }

    /**
     * Refresh user access token.
     */
    @Transactional(readOnly = true)
    public UserRefreshResponse refresh(UserRefreshRequest request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            throw new InvalidTokenException("Invalid or expired refresh token");
        }

        if (!jwtTokenProvider.isUserToken(refreshToken)) {
            throw new InvalidTokenException("Invalid token type for user refresh");
        }

        UUID userId = jwtTokenProvider.getSubjectId(refreshToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("User not found"));

        if (!user.getIsActive()) {
            throw new InvalidTokenException("User account is disabled");
        }

        List<String> roles = buildRolesClaim(user.getId());

        String accessToken = jwtTokenProvider.generateUserAccessToken(
                user.getId(), user.getName(), user.getEmail(), roles);

        log.debug("Access token refreshed for user: {}", user.getEmail());

        return UserRefreshResponse.builder()
                .accessToken(accessToken)
                .accessTokenExpiresAt(jwtTokenProvider.getExpiration(accessToken))
                .roles(roles)
                .build();
    }

    /**
     * Authenticate user from an external provider.
     * Called after successful external auth with user's email from the IdP.
     */
    @Transactional(readOnly = true)
    public LoginResponse authenticateExternalUser(String email, String providerCode) {
        String normalizedEmail = email.toLowerCase().trim();
        String normalizedProviderCode = providerCode.toUpperCase().trim();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElse(null);

        if (user == null) {
            log.warn("External login failed: user not found for email: {}", normalizedEmail);
            throw new BadRequestException("User not registered. Contact your administrator.");
        }

        if (!user.getIsActive()) {
            log.warn("External login failed: inactive user: {}", normalizedEmail);
            throw new BadRequestException("User account is disabled");
        }

        if (!normalizedProviderCode.equals(user.getProviderCode())) {
            log.warn("External login failed: provider mismatch for email: {}", normalizedEmail);
            throw new BadRequestException("Account is not mapped to this authentication provider");
        }

        authProviderService.validateProviderEnabledForUser(normalizedProviderCode);

        List<String> roles = buildRolesClaim(user.getId());

        String accessToken = jwtTokenProvider.generateUserAccessToken(
                user.getId(), user.getName(), user.getEmail(), roles);
        String refreshToken = jwtTokenProvider.generateUserRefreshToken(user.getId());

        log.info("User logged in via external provider: {} ({})", normalizedEmail, normalizedProviderCode);

        return LoginResponse.builder()
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .roles(roles)
                .accessToken(accessToken)
                .accessTokenExpiresAt(jwtTokenProvider.getExpiration(accessToken))
                .refreshToken(refreshToken)
                .refreshTokenExpiresAt(jwtTokenProvider.getExpiration(refreshToken))
                .build();
    }

    @Transactional(readOnly = true)
    public List<AuthenticationProvider> getEnabledProviders() {
        return authProviderService.findEnabled();
    }

    @Transactional(readOnly = true)
    public ExternalAuthStartResponse startExternalLogin(String providerCode, String redirectUri) {
        AuthenticationProvider provider = authProviderService.getEnabledExternalProvider(providerCode);
        String state = externalAuthStateService.issueState(provider.getCode());

        String authorizationUrl = buildAuthorizationUrl(provider, state, redirectUri);

        return ExternalAuthStartResponse.builder()
                .providerCode(provider.getCode())
                .state(state)
                .authorizationUrl(authorizationUrl)
                .build();
    }

    @Transactional(readOnly = true)
    public LoginResponse completeExternalLogin(String providerCode, String state, String code, String redirectUri) {
        AuthenticationProvider provider = authProviderService.getEnabledExternalProvider(providerCode);
        externalAuthStateService.validateAndConsume(state, providerCode);

        String externalEmail = resolveExternalEmail(provider, code, redirectUri);
        return authenticateExternalUser(externalEmail, providerCode);
    }

    private String buildAuthorizationUrl(AuthenticationProvider provider, String state, String redirectUri) {
        Map<String, Object> metadata = provider.getMetadata();
        String baseUrl = metadata != null && metadata.get("authorizationUrl") != null
                ? String.valueOf(metadata.get("authorizationUrl"))
                : "";
        String clientId = metadata != null && metadata.get("clientId") != null
                ? String.valueOf(metadata.get("clientId")).trim()
                : "";
        String scopes = metadata != null && metadata.get("scopes") != null
                ? normalizeScopes(metadata.get("scopes"))
                : "";

        if (baseUrl.isBlank()) {
            throw new BadRequestException("Authorization URL is not configured for provider " + provider.getCode());
        }

        if (clientId.isBlank()) {
            throw new BadRequestException("Client ID is not configured for provider " + provider.getCode());
        }

        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        String separator = baseUrl.contains("?") ? "&" : "?";

        appendQueryParam(urlBuilder, separator, "client_id", clientId);
        appendQueryParam(urlBuilder, "&", "state", state);
        appendQueryParam(urlBuilder, "&", "response_type", "code");

        if (redirectUri != null && !redirectUri.isBlank()) {
            appendQueryParam(urlBuilder, "&", "redirect_uri", redirectUri);
        }

        if (!scopes.isBlank()) {
            appendQueryParam(urlBuilder, "&", "scope", scopes);
        }

        return urlBuilder.toString();
    }

    private String normalizeScopes(Object scopesValue) {
        if (scopesValue instanceof String scopeString) {
            return scopeString.trim();
        }
        if (scopesValue instanceof List<?> scopeList) {
            return scopeList.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        return "";
    }

    private void appendQueryParam(StringBuilder urlBuilder, String separator, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        urlBuilder.append(separator)
                .append(key)
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private String resolveExternalEmail(AuthenticationProvider provider, String code, String redirectUri) {
        Map<String, Object> metadata = provider.getMetadata();
        String tokenUrl = readMetadataString(metadata, "tokenUrl");
        String clientId = readMetadataString(metadata, "clientId");
        String userInfoUrl = readMetadataString(metadata, "userInfoUrl");

        if (tokenUrl.isBlank() || clientId.isBlank() || userInfoUrl.isBlank()) {
            throw new BadRequestException("External provider metadata is incomplete for " + provider.getCode());
        }

        String clientSecret = readClientSecret(metadata);
        String accessToken = exchangeCodeForAccessToken(provider.getCode(), tokenUrl, clientId, clientSecret, code, redirectUri);
        String email = fetchEmailFromUserInfo(provider, userInfoUrl, accessToken);

        if (email.isBlank()) {
            throw new BadRequestException("Unable to determine email from external provider response");
        }

        return email;
    }

    private String exchangeCodeForAccessToken(
            String providerCode,
            String tokenUrl,
            String clientId,
            String clientSecret,
            String code,
            String redirectUri
    ) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("Missing authorization code");
        }

        StringBuilder formBody = new StringBuilder();
        appendFormParam(formBody, "client_id", clientId);
        appendFormParam(formBody, "client_secret", clientSecret);
        appendFormParam(formBody, "code", code);
        if (redirectUri != null && !redirectUri.isBlank()) {
            appendFormParam(formBody, "redirect_uri", redirectUri);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("External token exchange failed for provider {} with status {}", providerCode, response.statusCode());
                throw new BadRequestException("External token exchange failed");
            }

            JsonNode tokenNode = objectMapper.readTree(response.body());
            String accessToken = tokenNode.path("access_token").asText("").trim();
            if (accessToken.isBlank()) {
                throw new BadRequestException("External token exchange did not return access token");
            }

            return accessToken;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("External token exchange failed for provider {}", providerCode, e);
            throw new BadRequestException("External token exchange failed");
        }
    }

    private String fetchEmailFromUserInfo(AuthenticationProvider provider, String userInfoUrl, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(userInfoUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("User info request failed for provider {} with status {}", provider.getCode(), response.statusCode());
                throw new BadRequestException("External user info request failed");
            }

            JsonNode userNode = objectMapper.readTree(response.body());
            String email = userNode.path("email").asText("").trim();
            if (!email.isBlank()) {
                return email;
            }

            if (provider.getProviderType() == AuthenticationProvider.ProviderType.GITHUB) {
                return fetchGithubEmail(accessToken);
            }

            return "";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("External user info request failed for provider {}", provider.getCode(), e);
            throw new BadRequestException("External user info request failed");
        }
    }

    private String fetchGithubEmail(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/user/emails"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }

            JsonNode emailsNode = objectMapper.readTree(response.body());
            if (!emailsNode.isArray()) {
                return "";
            }

            for (JsonNode emailNode : emailsNode) {
                boolean primary = emailNode.path("primary").asBoolean(false);
                boolean verified = emailNode.path("verified").asBoolean(false);
                String email = emailNode.path("email").asText("").trim();
                if (primary && verified && !email.isBlank()) {
                    return email;
                }
            }

            for (JsonNode emailNode : emailsNode) {
                String email = emailNode.path("email").asText("").trim();
                if (!email.isBlank()) {
                    return email;
                }
            }
            return "";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    private String readMetadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return "";
        }
        return String.valueOf(metadata.get(key)).trim();
    }

    private String readClientSecret(Map<String, Object> metadata) {
        String encryptedSecret = readMetadataString(metadata, "clientSecretEncrypted");
        if (encryptedSecret.isBlank()) {
            throw new BadRequestException("Client secret is not configured for external provider");
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedSecret);
            return encryptionService.decrypt(decoded);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Client secret format is invalid");
        }
    }

    private void appendFormParam(StringBuilder formBody, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!formBody.isEmpty()) {
            formBody.append('&');
        }
        formBody.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    /**
     * Build roles claim for JWT.
     * System-wide roles: "ROLE"
     * Project-specific roles: "proj:<projectId>:ROLE"
     */
    private List<String> buildRolesClaim(UUID userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::toJwtClaim)
                .toList();
    }
}
