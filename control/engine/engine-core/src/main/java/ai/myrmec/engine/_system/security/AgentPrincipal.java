package ai.myrmec.engine._system.security;

import ai.myrmec.engine.agent.AgentInstance;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.util.UUID;

/**
 * Security principal representing an authenticated agent instance.
 */
@Getter
@RequiredArgsConstructor
public class AgentPrincipal implements Principal {

    private final AgentInstance instance;
    private final String agentName;

    @Override
    public String getName() {
        return agentName;
    }

    public UUID getInstanceId() {
        return instance.getId();
    }

    public UUID getAgentId() {
        return instance.getAgentId();
    }
}
