package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.dto.HeartbeatRequest;
import ai.myrmec.engine.agent.dto.HeartbeatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private static final String KEY_PREFIX = "myr_agent_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentRepository agentRepository;
    private final AgentInstanceRepository agentInstanceRepository;
    private final AgentProfileRepository agentProfileRepository;

    // ========== Admin Operations ==========

    /**
     * List all agents.
     */
    @Transactional(readOnly = true)
    public List<Agent> findAll() {
        return agentRepository.findAll();
    }

    /**
     * Create a new agent with auto-generated registration key.
     */
    @Transactional
    public AgentCreationResult createAgent(String name, String description, UUID profileId,
                                           UUID projectId, String modelOverride,
                                           Map<String, Object> config, Integer maxInstances) {
        // Validate name uniqueness
        if (agentRepository.existsByName(name)) {
            throw new BadRequestException("Agent with name '" + name + "' already exists");
        }

        // Validate profile exists
        if (!agentProfileRepository.existsById(profileId)) {
            throw ResourceNotFoundException.agentProfile(profileId);
        }

        // Generate registration key
        String registrationKey = generateRegistrationKey();

        Agent agent = new Agent();
        agent.setName(name);
        agent.setDescription(description);
        agent.setProfileId(profileId);
        agent.setProjectId(projectId);
        agent.setRegistrationKey(registrationKey);
        agent.setModelOverride(modelOverride);
        agent.setConfig(config);
        agent.setMaxInstances(maxInstances != null ? maxInstances : 1);
        agent.setStatus(Agent.Status.ACTIVE);

        agent = agentRepository.save(agent);
        log.info("Created agent: {} ({})", agent.getName(), agent.getId());

        return new AgentCreationResult(agent, registrationKey);
    }

    /**
     * Update an existing agent.
     */
    @Transactional
    public Agent updateAgent(UUID agentId, String name, String description, UUID profileId,
                             UUID projectId, String modelOverride,
                             Map<String, Object> config, Integer maxInstances, Agent.Status status) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentId));

        if (name != null && !name.equals(agent.getName())) {
            if (agentRepository.existsByName(name)) {
                throw new BadRequestException("Agent with name '" + name + "' already exists");
            }
            agent.setName(name);
        }

        if (description != null) {
            agent.setDescription(description);
        }

        if (profileId != null && !profileId.equals(agent.getProfileId())) {
            if (!agentProfileRepository.existsById(profileId)) {
                throw ResourceNotFoundException.agentProfile(profileId);
            }
            agent.setProfileId(profileId);
        }

        if (projectId != null) {
            agent.setProjectId(projectId);
        }

        if (modelOverride != null) {
            agent.setModelOverride(modelOverride.isEmpty() ? null : modelOverride);
        }

        if (config != null) {
            agent.setConfig(config);
        }

        if (maxInstances != null) {
            agent.setMaxInstances(maxInstances);
        }

        if (status != null) {
            agent.setStatus(status);
        }

        agent = agentRepository.save(agent);
        log.info("Updated agent: {} ({})", agent.getName(), agent.getId());
        return agent;
    }

    /**
     * Delete an agent and all its instances.
     */
    @Transactional
    public void deleteAgent(UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentId));

        // Delete all instances first
        List<AgentInstance> instances = agentInstanceRepository.findByAgentId(agentId);
        agentInstanceRepository.deleteAll(instances);

        agentRepository.delete(agent);
        log.info("Deleted agent: {} ({}) with {} instances", agent.getName(), agentId, instances.size());
    }

    /**
     * Regenerate registration key for an agent.
     */
    @Transactional
    public String regenerateRegistrationKey(UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentId));

        String newKey = generateRegistrationKey();
        agent.setRegistrationKey(newKey);
        agentRepository.save(agent);

        log.info("Regenerated registration key for agent: {} ({})", agent.getName(), agentId);
        return newKey;
    }

    /**
     * Count online instances for an agent.
     */
    @Transactional(readOnly = true)
    public int countOnlineInstances(UUID agentId) {
        return (int) agentInstanceRepository.countOnlineByAgentId(agentId);
    }

    private String generateRegistrationKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ========== Runtime Operations ==========

    /**
     * Record heartbeat from an agent instance.
     */
    @Transactional
    public HeartbeatResponse recordHeartbeat(UUID instanceId, HeartbeatRequest request) {
        AgentInstance instance = agentInstanceRepository.findById(instanceId)
                .orElseThrow(() -> ResourceNotFoundException.agentInstance(instanceId));

        instance.recordHeartbeat();
        agentInstanceRepository.save(instance);

        log.debug("Heartbeat received from instance: {}", instanceId);

        return HeartbeatResponse.builder()
                .ack(true)
                .serverTime(Instant.now())
                .build();
    }

    /**
     * Get an agent instance by ID.
     */
    @Transactional(readOnly = true)
    public AgentInstance getInstance(UUID instanceId) {
        return agentInstanceRepository.findById(instanceId)
                .orElseThrow(() -> ResourceNotFoundException.agentInstance(instanceId));
    }

    /**
     * Get an agent definition by ID.
     */
    @Transactional(readOnly = true)
    public Agent getAgent(UUID agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentId));
    }

    /**
     * Find agent by its registration key string.
     */
    @Transactional(readOnly = true)
    public Agent findAgentByRegistrationKey(String registrationKey) {
        return agentRepository.findByRegistrationKey(registrationKey)
                .orElseThrow(() -> new IllegalStateException(
                        "No agent definition found for registration key"));
    }

    /**
     * Create a new agent instance for an agent definition.
     */
    @Transactional
    public AgentInstance createInstance(UUID agentId, String hostname, String ipAddress,
                                         String runtimeVersion, Map<String, Object> metadata) {
        // Verify agent exists
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentId));

        AgentInstance instance = new AgentInstance();
        instance.setAgentId(agentId);
        instance.setHostname(hostname);
        instance.setIpAddress(ipAddress);
        instance.setRuntimeVersion(runtimeVersion);
        instance.setMetadata(metadata);
        instance.setStatus(AgentInstance.Status.ONLINE);
        instance.setRegisteredAt(Instant.now());
        instance.setLastHeartbeatAt(Instant.now());

        instance = agentInstanceRepository.save(instance);
        log.info("Agent instance registered: {} for agent {} ({})",
                instance.getId(), agent.getName(), agentId);
        return instance;
    }

    /**
     * Get all instances for an agent.
     */
    @Transactional(readOnly = true)
    public List<AgentInstance> getInstancesForAgent(UUID agentId) {
        return agentInstanceRepository.findByAgentId(agentId);
    }

    /**
     * Get all online instances.
     */
    @Transactional(readOnly = true)
    public List<AgentInstance> getOnlineInstances() {
        return agentInstanceRepository.findByStatus(AgentInstance.Status.ONLINE);
    }

    /**
     * Mark stale instances as offline.
     */
    @Transactional
    public int markStaleInstancesOffline(Instant threshold) {
        List<AgentInstance> staleInstances = agentInstanceRepository.findStaleInstances(threshold);
        for (AgentInstance instance : staleInstances) {
            instance.markOffline();
            agentInstanceRepository.save(instance);
        }
        if (!staleInstances.isEmpty()) {
            log.info("Marked {} stale instances as offline", staleInstances.size());
        }
        return staleInstances.size();
    }
}
