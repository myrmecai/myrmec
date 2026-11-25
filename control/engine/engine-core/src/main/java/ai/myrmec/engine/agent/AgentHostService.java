// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Service that manages {@link AgentHost} entities (CRUD, registration-key
 * generation) and its {@link AgentHostInstance} runtime rows.
 *
 * <p>This service manages agent <em>hosts</em> (the durable definition a
 * Supervisor process registers against) and their live instances (the
 * heartbeat/liveness owner). The unified protocol's session allocation
 * tracks the per-session runtime state.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentHostService {

    private static final String KEY_PREFIX = "myr_agent_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentHostRepository agentHostRepository;
    private final AgentHostInstanceRepository agentHostInstanceRepository;
    private final ModelAccessModeValidator modelAccessModeValidator;

    // ========== Admin Operations ==========

    /**
     * List all agent hosts.
     */
    @Transactional(readOnly = true)
    public List<AgentHost> findAll() {
        return agentHostRepository.findAll();
    }

    /**
     * Create a new agent host with auto-generated registration key.
     *
     * @param hostType MANAGED for cluster/headless supervisors (default);
     *                 LOCAL pre-provisions a per-user IDE seat — the owner
     *                 is unknown at creation and arrives when the user's
     *                 plugin connects (host.open stamps the instance).
     *                 LOCAL hosts are per-user, never Team-scoped.
     */
    @Transactional
    public AgentHostCreationResult createAgent(String name, String description,
                                           UUID teamId, Integer maxAgents,
                                           ModelAccessMode modelAccessMode,
                                           AgentHostType hostType) {
        // Validate name uniqueness
        if (agentHostRepository.existsByName(name)) {
            throw new BadRequestException("Agent with name '" + name + "' already exists");
        }

        AgentHostType type = hostType != null ? hostType : AgentHostType.MANAGED;
        if (type == AgentHostType.LOCAL && teamId != null) {
            throw new BadRequestException("A LOCAL agent host cannot be Team-scoped");
        }

        // Credential-envelope design §5.2: validate the requested mode against
        // the gateway configuration + governance mandate BEFORE persisting.
        ModelAccessMode mode = modelAccessMode != null ? modelAccessMode : ModelAccessMode.DIRECT;
        modelAccessModeValidator.validate(type, mode);

        // Generate registration key
        String registrationKey = generateRegistrationKey();

        AgentHost agentHost = new AgentHost();
        agentHost.setName(name);
        agentHost.setDescription(description);
        agentHost.setTeamId(teamId);
        agentHost.setRegistrationKey(registrationKey);
        agentHost.setMaxAgents(maxAgents != null ? maxAgents : 1);
        agentHost.setStatus(AgentHost.Status.ACTIVE);
        agentHost.setModelAccessMode(mode);
        agentHost.setHostType(type);

        agentHost = agentHostRepository.save(agentHost);
        log.info("Created agent: {} ({})", agentHost.getName(), agentHost.getId());

        return new AgentHostCreationResult(agentHost, registrationKey);
    }

    /**
     * Update an existing agent host.
     */
    @Transactional
    public AgentHost updateAgent(UUID agentHostId, String name, String description,
                             UUID teamId, Integer maxAgents, AgentHost.Status status,
                             ModelAccessMode modelAccessMode) {
        AgentHost agentHost = agentHostRepository.findById(agentHostId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentHostId));

        if (name != null && !name.equals(agentHost.getName())) {
            if (agentHostRepository.existsByName(name)) {
                throw new BadRequestException("Agent with name '" + name + "' already exists");
            }
            agentHost.setName(name);
        }

        if (description != null) {
            agentHost.setDescription(description);
        }

        if (teamId != null) {
            agentHost.setTeamId(teamId);
        }

        if (maxAgents != null) {
            agentHost.setMaxAgents(maxAgents);
        }

        if (status != null) {
            agentHost.setStatus(status);
        }

        // Credential-envelope design §5.2: a mode change is validated against
        // the matrix + mandate (null = no change). The PLATFORM_ADMIN-only
        // guard for this path lives in the admin controller (method-level).
        if (modelAccessMode != null && modelAccessMode != agentHost.getModelAccessMode()) {
            modelAccessModeValidator.validate(agentHost.getHostType(), modelAccessMode);
            agentHost.setModelAccessMode(modelAccessMode);
            log.info("Model access mode of host {} set to {}", agentHostId, modelAccessMode);
        }

        agentHost = agentHostRepository.save(agentHost);
        log.info("Updated agent: {} ({})", agentHost.getName(), agentHost.getId());
        return agentHost;
    }

    /**
     * Delete an agent host and all its instances.
     */
    @Transactional
    public void deleteAgent(UUID agentHostId) {
        AgentHost agentHost = agentHostRepository.findById(agentHostId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentHostId));

        // Delete all instances first
        List<AgentHostInstance> instances = agentHostInstanceRepository
                .findByAgentHostIdOrderByOpenedAtDesc(agentHostId);
        agentHostInstanceRepository.deleteAll(instances);

        agentHostRepository.delete(agentHost);
        log.info("Deleted agent: {} ({}) with {} instances", agentHost.getName(), agentHostId, instances.size());
    }

    /**
     * Regenerate registration key for an agent host.
     */
    @Transactional
    public String regenerateRegistrationKey(UUID agentHostId) {
        AgentHost agentHost = agentHostRepository.findById(agentHostId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentHostId));

        String newKey = generateRegistrationKey();
        agentHost.setRegistrationKey(newKey);
        agentHostRepository.save(agentHost);

        log.info("Regenerated registration key for agent: {} ({})", agentHost.getName(), agentHostId);
        return newKey;
    }

    /**
     * Count live OPEN instances for an agent host. Used by the #88
     * availability check (an OPEN instance means the host has live capacity).
     */
    @Transactional(readOnly = true)
    public int countOnlineInstances(UUID agentHostId) {
        return (int) agentHostInstanceRepository.countByAgentHostIdAndStatus(
                agentHostId, AgentHostInstance.Status.OPEN);
    }

    /**
     * Count connected instances for a host — OPEN + RECOVERING rows (a live
     * supervisor run, idle or busy). Used by the #88 availability check.
     */
    @Transactional(readOnly = true)
    public int countConnectedInstances(UUID agentHostId) {
        long open = agentHostInstanceRepository.countByAgentHostIdAndStatus(
                agentHostId, AgentHostInstance.Status.OPEN);
        long recovering = agentHostInstanceRepository.countByAgentHostIdAndStatus(
                agentHostId, AgentHostInstance.Status.RECOVERING);
        return (int) (open + recovering);
    }

    private String generateRegistrationKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ========== Local agent operations ==========

    /**
     * Create or reuse the ephemeral local {@link AgentHost} for a signed-in user.
     *
     * <p>Local-owner model (§3.7/§4.1): the ONLY owner record lives on
     * {@code agent_host_instances.owner_user_id} — the host row is a
     * per-user placeholder. Matching is by live-instance ownership: if the
     * user already has an OPEN local instance, its host is reused (profile
     * may be refreshed; the host is re-asserted ACTIVE). Otherwise a fresh
     * LOCAL host is created WITHOUT an owner — the owner stamps the
     * instance when the plugin's first {@code host.open} (carrying
     * {@code ownerUserId}) arrives. A fresh local host still gets a durable
     * registration key (LOCAL hosts never consume it — registration keys
     * are MANAGED-only); the extension authenticates with short-lived
     * agent JWTs minted against the returned host/instance.</p>
     *
    /**
     * Legacy local-agent-instance path (pre-unified-protocol): retains the
     * explicit Team scope. The unified host-register path uses the 2-arg
     * overload below (system-wide, team derived from the bearer token).
     */
    @Transactional
    public AgentHost upsertLocalAgentHost(UUID userId, UUID teamId, String hostname) {
        AgentHost host = upsertLocalAgentHost(userId, hostname);
        host.setTeamId(teamId);
        return agentHostRepository.save(host);
    }

    /**
     * @param userId    the authenticated user who owns the local agent
     * @param hostname  host identity reported by the IDE extension (e.g. the
     *                  machine name or VS Code session id); cosmetic, used
     *                  only to build the display name
     * @return the local agent host definition (existing or newly created)
     */
    @Transactional
    public AgentHost upsertLocalAgentHost(UUID userId, String hostname) {
        if (userId == null) {
            throw new BadRequestException("userId is required for a local agent host");
        }

        // Per-USER matching rides the live instance (local-owner model):
        // the host whose OPEN instance this user owns is theirs.
        AgentHostInstance existingInstance =
                agentHostInstanceRepository.findByOwnerUserIdAndStatus(
                        userId, AgentHostInstance.Status.OPEN).orElse(null);
        if (existingInstance != null) {
            AgentHost host = agentHostRepository.findById(existingInstance.getAgentHostId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Live instance " + existingInstance.getId()
                                    + " references a missing host"));
            if (hostname != null) {
                host.setName(buildLocalHostName(userId, hostname));
            }
            host.setStatus(AgentHost.Status.ACTIVE);
            log.info("Reused local agent host {} for user {}", host.getId(), userId);
            return agentHostRepository.save(host);
        }

        AgentHost host = new AgentHost();
        // Keep the per-user name scheme, but a fresh host can coexist with a
        // previous (now unmatchable) host of the same user+hostname — e.g.
        // the staleness sweep closed the user's stale OPEN instance, the
        // laptop resumed, and the plugin re-registered. Suffix on collision
        // instead of a 500 on the name unique index.
        String name = buildLocalHostName(userId, hostname);
        if (agentHostRepository.existsByName(name)) {
            name = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        host.setName(name);
        host.setDescription("Local IDE agent for user " + userId);
        // A LOCAL host is per-user and system-wide (no team scope): the user's
        // Team membership is already carried by the bearer token's role
        // claims and enforced at the API layer.
        host.setTeamId(null);
        host.setRegistrationKey(generateRegistrationKey());
        host.setMaxAgents(1);
        host.setStatus(AgentHost.Status.ACTIVE);
        host.setHostType(AgentHostType.LOCAL);

        host = agentHostRepository.save(host);
        log.info("Created local agent host {} for user {} — owner stamps "
                        + "the instance at host.open",
                host.getId(), userId);
        return host;
    }

    private String buildLocalHostName(UUID userId, String hostname) {
        String suffix = hostname != null && !hostname.isBlank() ? hostname : "local";
        return "local-" + userId.toString().substring(0, 8) + "-" + suffix;
    }

    // ========== Runtime Operations ==========

    /**
     * Get an agent host by ID.
     */
    @Transactional(readOnly = true)
    public AgentHost getAgent(UUID agentHostId) {
        return agentHostRepository.findById(agentHostId)
                .orElseThrow(() -> ResourceNotFoundException.agent(agentHostId));
    }

    /**
     * Find agent host by its registration key string.
     */
    @Transactional(readOnly = true)
    public AgentHost findAgentByRegistrationKey(String registrationKey) {
        return agentHostRepository.findByRegistrationKey(registrationKey)
                .orElseThrow(() -> new IllegalStateException(
                        "No agent definition found for registration key"));
    }
}