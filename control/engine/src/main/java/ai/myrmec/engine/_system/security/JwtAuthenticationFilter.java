package ai.myrmec.engine._system.security;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.registration.RegistrationKeyService;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final AgentRepository agentRepository;
    private final AgentInstanceRepository agentInstanceRepository;
    private final UserRepository userRepository;
    private final RegistrationKeyService registrationKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            // Only accept access tokens for API authentication
            if (jwtTokenProvider.validateAccessToken(token)) {
                if (jwtTokenProvider.isAgentToken(token)) {
                    authenticateAgent(token);
                } else if (jwtTokenProvider.isUserToken(token)) {
                    authenticateUser(token);
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticateAgent(String token) {
        UUID instanceId = jwtTokenProvider.getSubjectId(token);
        String agentName = jwtTokenProvider.getName(token);
        AgentInstance instance = agentInstanceRepository.findById(instanceId).orElse(null);

        if (instance != null && !isRegistrationKeyRevoked(instance)) {
            AgentPrincipal principal = new AgentPrincipal(instance, agentName);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_AGENT"))
                    );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Authenticated agent instance: {} (agent: {})", instanceId, agentName);
        }
    }

    private void authenticateUser(String token) {
        UUID userId = jwtTokenProvider.getSubjectId(token);
        User user = userRepository.findById(userId).orElse(null);

        if (user != null && user.getIsActive()) {
            String name = jwtTokenProvider.getName(token);
            String email = jwtTokenProvider.getEmail(token);
            List<String> roles = jwtTokenProvider.getRoles(token);

            UserPrincipal principal = new UserPrincipal(userId, name, email, roles);

            // Build authorities from roles
            List<SimpleGrantedAuthority> authorities = buildAuthorities(roles);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            authorities
                    );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Authenticated user: {}", email);
        } else {
            log.warn("Permission denied: user not found or inactive for userId: {}", userId);
        }
    }

    /**
     * Build Spring Security authorities from JWT role claims.
     *
     * <p>Claims use the scope-prefixed format: {@code sys:ROLE},
     * {@code grp:<id>:ROLE}, {@code proj:<id>:ROLE}. System-scoped roles map to
     * {@code ROLE_<role>} authorities for use with {@code hasRole(...)}.
     * Group- and project-scoped roles are surfaced via {@link UserPrincipal}
     * helpers and the access evaluators rather than as authorities.
     */
    private List<SimpleGrantedAuthority> buildAuthorities(List<String> roles) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));

        if (roles != null) {
            for (String role : roles) {
                if (role != null && role.startsWith("sys:")) {
                    String roleName = role.substring("sys:".length());
                    if (!roleName.isEmpty()) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));
                    }
                }
                // Group- and project-scoped claims are not exposed as authorities;
                // controllers use the access evaluators instead.
            }
        }

        return authorities;
    }

    /**
     * Check if the agent's registration key has been revoked.
     * This provides token revocation via registration key revocation.
     */
    private boolean isRegistrationKeyRevoked(AgentInstance instance) {
        // Look up the agent definition to get the registration key
        Agent agent = agentRepository.findById(instance.getAgentId()).orElse(null);
        if (agent == null || agent.getRegistrationKey() == null) {
            return false;
        }
        return registrationKeyService.findByKeyValue(agent.getRegistrationKey())
                .map(key -> !key.isValid())
                .orElse(false); // Key not in table = not revoked
    }
}
