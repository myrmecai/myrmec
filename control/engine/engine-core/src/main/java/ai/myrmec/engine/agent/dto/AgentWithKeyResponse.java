package ai.myrmec.engine.agent.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Response DTO for newly created agent with registration key.
 * The registration key is only returned once upon creation.
 */
@Data
@Builder
public class AgentWithKeyResponse {

    private AgentResponse agent;

    /**
     * The plaintext registration key.
     * Only returned on create - never stored or returned again.
     */
    private String registrationKey;
}
