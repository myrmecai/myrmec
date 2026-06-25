package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.dto.HeartbeatRequest;
import ai.myrmec.engine.agent.dto.HeartbeatResponse;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationEventReason;
import ai.myrmec.engine.conversation.ConversationEventService;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.node.NodeRegistryService;
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

    private final AgentHostRepository agentRepository;
    private final AgentRepository agentInstanceRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationEventService conversationEventService;
    private final NodeRegistryService nodeRegistryService;

    // ========== Admin Operations ==========

    /**
     * List all agents.
     */
    @Transactional(readOnly = true)
    public List<AgentHost> findAll() {
        return agentRepository.findAll();
    }

    /**
     * Create a new agent with auto-generated registration key.
     */
    @Transactional
    public AgentCreationResult createAgent(String name, String description, UUID profileId,
                                           UUID projectId, String modelOverride,
                                           Map<String, Object> config, Integer maxAgents) {
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

        AgentHost agent = new AgentHost();
        agent.setName(name);
        agent.setDescription(description);
        agent.setProfileId(profileId);
        agent.setProjectId(projectId);
        agent.setRegistrationKey(registrationKey);
        agent.setModelOverride(modelOverride);
        agent.setConfig(config);
        agent.setMaxAgents(maxAgents != null ? maxAgents : 1);
        agent.setStatus(AgentHost.Status.ACTIVE);

        agent = agentRepository.save(agent);
        log.info("Created agent: {} ({})", agent.getName(), agent.getId());

        return new AgentCreationResult(agent, registrationKey);
    }

    /**
     * Update an existing agent.
     */
    @Transactional
    public AgentHost updateAgent(UUID agentId, String name, String description, UUID profileId,
                             UUID projectId, String modelOverride,
                             Map<String, Object> config, Integer maxAgents, AgentHost.Status status) {
        AgentHost agent = agentRepository.findById(agentId)
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

        if (maxAgents != null) {
            agent.setMaxAgents(maxAgents);
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
        AgentHost agent = agentRepository.findById(agentId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentId));

        // Delete all instances first
        List<Agent> instances = agentInstanceRepository.findByAgentHostId(agentId);
        agentInstanceRepository.deleteAll(instances);

        agentRepository.delete(agent);
        log.info("Deleted agent: {} ({}) with {} instances", agent.getName(), agentId, instances.size());
    }

    /**
     * Regenerate registration key for an agent.
     */
    @Transactional
    public String regenerateRegistrationKey(UUID agentId) {
        AgentHost agent = agentRepository.findById(agentId)
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
        return (int) agentInstanceRepository.countOnlineByAgentHostId(agentId);
    }

    /**
     * Count connected (non-{@code DEAD}) instances for a host — workers with a
     * live control socket, idle or busy. Used by the #88 availability check.
     */
    @Transactional(readOnly = true)
    public int countConnectedInstances(UUID agentId) {
        return (int) agentInstanceRepository.countConnectedByAgentHostId(agentId);
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
        Agent instance = agentInstanceRepository.findById(instanceId)
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
     * Record a host.announce from a Supervisor: overwrite the host's advertised
     * provisions and reported capacity. The host is the source of truth, so
     * each announce replaces the previous values (re-asserted on reconnect).
     */
    @Transactional
    public void recordHostAnnounce(UUID hostId, Map<String, Object> provisions,
                                   Map<String, Object> reportedCapacity) {
        AgentHost host = agentRepository.findById(hostId)
                .orElseThrow(() -> ResourceNotFoundException.agent(hostId));
        host.setProvisions(provisions);
        host.setReportedCapacity(reportedCapacity);
        host.setControlNodeId(nodeRegistryService.getSelfNodeId());
        agentRepository.save(host);
        log.debug("Recorded host.announce for host {} ({})", host.getName(), hostId);
    }

    /**
     * Get an agent instance by ID.
     */
    @Transactional(readOnly = true)
    public Agent getInstance(UUID instanceId) {
        return agentInstanceRepository.findById(instanceId)
                .orElseThrow(() -> ResourceNotFoundException.agentInstance(instanceId));
    }

    /**
     * Get an agent definition by ID.
     */
    @Transactional(readOnly = true)
    public AgentHost getAgent(UUID agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentId));
    }

    /**
     * Find agent by its registration key string.
     */
    @Transactional(readOnly = true)
    public AgentHost findAgentByRegistrationKey(String registrationKey) {
        return agentRepository.findByRegistrationKey(registrationKey)
                .orElseThrow(() -> new IllegalStateException(
                        "No agent definition found for registration key"));
    }

    /**
     * Create a new agent instance for an agent definition.
     */
    @Transactional
    public Agent createInstance(UUID agentId, String hostname, String ipAddress,
                                         String runtimeVersion, Map<String, Object> metadata) {
        // Verify agent exists
        AgentHost agent = agentRepository.findById(agentId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentId));

        Agent instance = new Agent();
        instance.setAgentHostId(agentId);
        instance.setHostname(hostname);
        instance.setIpAddress(ipAddress);
        instance.setRuntimeVersion(runtimeVersion);
        instance.setMetadata(metadata);
        instance.setStatus(Agent.Status.IDLE);
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
    public List<Agent> getInstancesForAgent(UUID agentId) {
        return agentInstanceRepository.findByAgentHostId(agentId);
    }

    /**
     * Get all online instances.
     */
    @Transactional(readOnly = true)
    public List<Agent> getOnlineInstances() {
        return agentInstanceRepository.findByStatus(Agent.Status.IDLE);
    }

    /**
     * Atomically reserve a specific warm worker for a conversation
     * (IDLE → RESERVED), pinning the conversation and profile version at
     * reserve-time (agent-concurrency §9.5). Returns {@code true} if this
     * caller won the claim; {@code false} if the worker was no longer IDLE
     * (lost the race or already bound), in which case the caller should try
     * another candidate.
     */
    @Transactional
    public boolean reserveInstance(UUID instanceId, UUID conversationId, UUID profileVersionId) {
        int claimed = agentInstanceRepository.reserveIfIdle(
                instanceId, Agent.Status.IDLE, Agent.Status.RESERVED,
                conversationId, profileVersionId, Instant.now());
        if (claimed > 0) {
            log.debug("Reserved agent {} for conversation {} (profileVersion {})",
                    instanceId, conversationId, profileVersionId);
            UUID hostId = agentInstanceRepository.findById(instanceId)
                    .map(Agent::getAgentHostId).orElse(null);
            conversationEventService.record(conversationId, instanceId, hostId,
                    Agent.Status.IDLE, Agent.Status.RESERVED, ConversationEventReason.RESERVED);
            return true;
        }
        log.debug("Could not reserve agent {} — no longer IDLE", instanceId);
        return false;
    }

    /**
     * Release a worker back into the warm pool (→ IDLE, binding cleared).
     * Idempotent: a no-op if the worker is already idle or gone.
     */
    @Transactional
    public void releaseInstance(UUID instanceId) {
        agentInstanceRepository.findById(instanceId).ifPresent(instance -> {
            // Don't revive a DEAD worker — it was reaped for a reason
            // (host lost, transport error) and must not re-enter the pool.
            if (instance.getStatus() == Agent.Status.DEAD) {
                log.debug("Skipping release of DEAD worker {}", instanceId);
                return;
            }
            UUID conversationId = instance.getConversationId();
            UUID hostId = instance.getAgentHostId();
            Agent.Status fromState = instance.getStatus();
            instance.release();
            agentInstanceRepository.save(instance);
            conversationEventService.record(conversationId, instanceId, hostId,
                    fromState, Agent.Status.IDLE, ConversationEventReason.RELEASED);
            log.debug("Released agent {} back to the warm pool", instanceId);
        });
    }

    /**
     * Flip a reserved worker to {@code BOUND} once its conversation socket
     * has attached to the home node (agent-concurrency §9.4/§9.5), and pin
     * the home node on both the worker and the conversation so the
     * cross-node router can address turns + streamed output to this replica.
     *
     * <p>Defensive: only attaches when the worker is still reserved for the
     * same conversation it is attaching for. A worker attaching for a
     * conversation it was never bound to (or after it was already released)
     * is rejected with {@code false}; the caller closes the socket.</p>
     *
     * @return {@code true} if the worker was flipped to BOUND, {@code false}
     *         if the worker is gone or no longer bound to that conversation
     */
    @Transactional
    public boolean attachConversation(UUID instanceId, UUID conversationId, String homeNodeId) {
        Agent instance = agentInstanceRepository.findById(instanceId).orElse(null);
        if (instance == null) {
            log.warn("conversation.attach for unknown agent instance {} — rejecting", instanceId);
            return false;
        }
        if (instance.getConversationId() == null
                || !instance.getConversationId().equals(conversationId)) {
            log.warn("conversation.attach mismatch — agent {} is bound to {} not {}; rejecting",
                    instanceId, instance.getConversationId(), conversationId);
            return false;
        }
        Agent.Status fromState = instance.getStatus();

        // Idempotent re-attach (agent-concurrency §9.11): the worker is already
        // BOUND to this conversation on this same home node. The Agent
        // Supervisor reconnected its conversation socket after a transient drop
        // and re-sent conversation.attach. Treat it as a liveness event — stay
        // BOUND, record CONVERSATION_REATTACHED, and do NOT re-emit
        // INSTANCE_BOUND (no duplicate bind for the same tuple). The caller
        // re-registers the fresh socket regardless.
        if (fromState == Agent.Status.BOUND
                && homeNodeId != null && homeNodeId.equals(instance.getHomeNodeId())) {
            conversationEventService.record(conversationId, instanceId, instance.getAgentHostId(),
                    Agent.Status.BOUND, Agent.Status.BOUND, ConversationEventReason.CONVERSATION_REATTACHED);
            log.info("Agent {} re-attached conversation {} on same home node {} (CONVERSATION_REATTACHED)",
                    instanceId, conversationId, homeNodeId);
            return true;
        }

        instance.markBound(homeNodeId);
        agentInstanceRepository.save(instance);
        conversationEventService.record(conversationId, instanceId, instance.getAgentHostId(),
                fromState, Agent.Status.BOUND, ConversationEventReason.INSTANCE_BOUND);

        conversationRepository.findById(conversationId).ifPresent(conversation -> {
            conversation.setHomeNodeId(homeNodeId);
            conversation.setAgentHostId(instance.getAgentHostId());
            conversationRepository.save(conversation);
        });
        log.info("Agent {} BOUND to conversation {} on home node {}",
                instanceId, conversationId, homeNodeId);
        return true;
    }

    /**
     * Consume an {@code agent.bind.ack} from the Agent Host: the host
     * received the {@code agent.bind} and its worker is now dialing the home
     * node, so flip the reserved worker to {@code CONNECTING} and restart the
     * reaper clock (the connect-timeout window runs from here, not from the
     * original reserve). Only a worker still {@code RESERVED} for the same
     * conversation transitions; an ack arriving after the worker already
     * attached ({@code BOUND}) or was released is a harmless no-op.
     *
     * @return {@code true} if the worker advanced to CONNECTING (or was
     *         already past it for this conversation); {@code false} if the
     *         ack does not match a live reservation
     */
    @Transactional
    public boolean confirmBind(UUID instanceId, UUID conversationId) {
        Agent instance = agentInstanceRepository.findById(instanceId).orElse(null);
        if (instance == null || instance.getConversationId() == null
                || !instance.getConversationId().equals(conversationId)) {
            log.warn("agent.bind.ack mismatch — agent {} not reserved for {}; ignoring",
                    instanceId, conversationId);
            return false;
        }
        if (instance.getStatus() == Agent.Status.RESERVED) {
            instance.markConnecting();
            agentInstanceRepository.save(instance);
            conversationEventService.record(conversationId, instanceId, instance.getAgentHostId(),
                    Agent.Status.RESERVED, Agent.Status.CONNECTING, ConversationEventReason.BIND_ACKED);
            log.debug("Agent {} CONNECTING for conversation {} (bind acked)",
                    instanceId, conversationId);
        }
        return true;
    }

    /**
     * Consume an {@code agent.bind.nack} from the Agent Host: the host cannot
     * serve this binding (worker spawn failed, capacity gone, etc.), so
     * release the reserved worker straight back to {@code IDLE} for the
     * dispatcher to re-pick another candidate. Only a worker still bound to
     * the same conversation in a transient state ({@code RESERVED}/
     * {@code CONNECTING}) is released.
     *
     * @return {@code true} if the worker was released; {@code false} if the
     *         nack does not match a live reservation
     */
    @Transactional
    public boolean rejectBind(UUID instanceId, UUID conversationId, String reason) {
        Agent instance = agentInstanceRepository.findById(instanceId).orElse(null);
        if (instance == null || instance.getConversationId() == null
                || !instance.getConversationId().equals(conversationId)) {
            log.warn("agent.bind.nack mismatch — agent {} not reserved for {}; ignoring",
                    instanceId, conversationId);
            return false;
        }
        if (instance.getStatus() == Agent.Status.RESERVED
                || instance.getStatus() == Agent.Status.CONNECTING) {
            Agent.Status fromState = instance.getStatus();
            UUID hostId = instance.getAgentHostId();
            instance.release();
            agentInstanceRepository.save(instance);
            conversationEventService.record(conversationId, instanceId, hostId,
                    fromState, Agent.Status.IDLE, ConversationEventReason.BIND_NACKED);
            log.info("Agent {} released after bind.nack for conversation {} (reason: {})",
                    instanceId, conversationId, reason);
        }
        return true;
    }

    /**
     * Mark stale instances as offline.
     */
    @Transactional
    public int markStaleInstancesOffline(Instant threshold) {
        List<Agent> staleInstances = agentInstanceRepository.findStaleInstances(threshold);
        for (Agent instance : staleInstances) {
            instance.markOffline();
            agentInstanceRepository.save(instance);
        }
        if (!staleInstances.isEmpty()) {
            log.info("Marked {} stale instances as offline", staleInstances.size());
        }
        return staleInstances.size();
    }
}
