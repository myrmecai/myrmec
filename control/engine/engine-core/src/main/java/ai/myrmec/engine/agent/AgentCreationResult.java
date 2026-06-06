package ai.myrmec.engine.agent;

/**
 * Result of agent creation, containing the agent and its plaintext registration key.
 * The key is only available at creation time and never stored/returned again.
 */
public record AgentCreationResult(Agent agent, String registrationKey) {
}
