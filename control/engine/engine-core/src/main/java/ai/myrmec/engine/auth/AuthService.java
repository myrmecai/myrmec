package ai.myrmec.engine.auth;

import ai.myrmec.engine._system.exception.InvalidRegistrationKeyException;
import ai.myrmec.engine._system.exception.InvalidTokenException;
import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.agent.AgentService;
import ai.myrmec.engine.auth.dto.RefreshRequest;
import ai.myrmec.engine.auth.dto.RefreshResponse;
import ai.myrmec.engine.auth.dto.RegisterRequest;
import ai.myrmec.engine.auth.dto.RegisterResponse;
import ai.myrmec.engine.registration.RegistrationKey;
import ai.myrmec.engine.registration.RegistrationKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final RegistrationKeyService registrationKeyService;
    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final AgentInstanceRepository agentInstanceRepository;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Register a new agent instance using a registration key.
     * The registration key must be associated with an existing Agent definition.
     * Returns access token (short-lived) and refresh token (long-lived).
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String keyValue = request.getRegistrationKey();

        // Find the agent definition by registration key
        Agent agent = agentRepository.findByRegistrationKey(keyValue)
                .orElseThrow(() -> new InvalidRegistrationKeyException("Invalid registration key"));

        if (agent.getStatus() != Agent.Status.ACTIVE) {
            throw new InvalidRegistrationKeyException("Agent definition is not active");
        }

        // Optionally validate against RegistrationKey table for revocation/expiry
        registrationKeyService.findByKeyValue(keyValue)
                .filter(rk -> !rk.isValid())
                .ifPresent(rk -> {
                    throw new InvalidRegistrationKeyException("Registration key is expired or revoked");
                });

        // Create agent instance
        AgentInstance instance = agentService.createInstance(
                agent.getId(),
                request.getHostname(),
                request.getIpAddress(),
                request.getRuntimeVersion(),
                request.getMetadata()
        );

        // Generate tokens with instance ID
        String accessToken = jwtTokenProvider.generateAgentAccessToken(instance.getId(), agent.getName());
        String refreshToken = jwtTokenProvider.generateAgentRefreshToken(instance.getId());

        log.info("Agent instance registered: {} for agent {} ({})",
                instance.getId(), agent.getName(), agent.getId());

        return RegisterResponse.builder()
                .instanceId(instance.getId())
                .accessToken(accessToken)
                .accessTokenExpiresAt(jwtTokenProvider.getExpiration(accessToken))
                .refreshToken(refreshToken)
                .refreshTokenExpiresAt(jwtTokenProvider.getExpiration(refreshToken))
                .build();
    }

    /**
     * Refresh access token using a valid refresh token.
     * This method handles agent instance token refresh only.
     */
    @Transactional(readOnly = true)
    public RefreshResponse refresh(RefreshRequest request) {
        String refreshToken = request.getRefreshToken();

        // Validate refresh token
        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            throw new InvalidTokenException("Invalid or expired refresh token");
        }

        // Ensure it's an agent token
        if (!jwtTokenProvider.isAgentToken(refreshToken)) {
            throw new InvalidTokenException("Invalid token type for agent refresh");
        }

        UUID instanceId = jwtTokenProvider.getSubjectId(refreshToken);

        // Verify instance exists
        AgentInstance instance = agentInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new InvalidTokenException("Agent instance not found"));

        // Load the agent and verify registration key is still valid
        Agent agent = agentRepository.findById(instance.getAgentId())
                .orElseThrow(() -> new InvalidTokenException("Agent definition not found"));

        // Check if registration key has been revoked
        if (agent.getRegistrationKey() != null) {
            registrationKeyService.findByKeyValue(agent.getRegistrationKey())
                    .filter(key -> !key.isValid())
                    .ifPresent(key -> {
                        throw new InvalidTokenException("Registration key has been revoked");
                    });
        }

        // Generate new access token
        String accessToken = jwtTokenProvider.generateAgentAccessToken(instanceId, agent.getName());

        log.debug("Access token refreshed for agent instance: {}", instanceId);

        return RefreshResponse.builder()
                .accessToken(accessToken)
                .accessTokenExpiresAt(jwtTokenProvider.getExpiration(accessToken))
                .build();
    }
}
