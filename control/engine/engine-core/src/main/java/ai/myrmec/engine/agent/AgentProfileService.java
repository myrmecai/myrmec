package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceInUseException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.tool.Tool;
import ai.myrmec.engine.tool.ToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentProfileService {

    private final AgentProfileRepository profileRepository;
    private final AgentRepository agentRepository;
    private final ToolRepository toolRepository;

    /**
     * Get all agent profiles.
     */
    @Transactional(readOnly = true)
    public List<AgentProfile> getAllProfiles() {
        return profileRepository.findAllWithTools();
    }

    /**
     * Get all active agent profiles.
     */
    @Transactional(readOnly = true)
    public List<AgentProfile> getActiveProfiles() {
        return profileRepository.findAllActiveWithTools();
    }

    /**
     * Get a profile by ID.
     */
    @Transactional(readOnly = true)
    public AgentProfile getProfile(UUID id) {
        return profileRepository.findByIdWithTools(id)
                .orElseThrow(() -> ResourceNotFoundException.agentProfile(id));
    }

    /**
     * Create a new agent profile.
     */
    @Transactional
    public AgentProfile createProfile(String name, String description,
                                       List<String> capabilities, List<String> supportedTools,
                                       Set<String> toolCodes, String systemPrompt, String defaultModel) {
        if (profileRepository.existsByName(name)) {
            throw new DuplicateResourceException("AgentProfile", "name", name);
        }

        AgentProfile profile = new AgentProfile();
        profile.setName(name);
        profile.setDescription(description);
        profile.setCapabilities(capabilities);
        profile.setSupportedTools(supportedTools);
        profile.setSystemPrompt(systemPrompt);
        profile.setDefaultModel(defaultModel);
        profile.setStatus(AgentProfile.Status.ACTIVE);

        // Resolve and assign tools
        if (toolCodes != null && !toolCodes.isEmpty()) {
            Set<Tool> tools = new HashSet<>(toolRepository.findAllById(toolCodes));
            profile.setTools(tools);
        }

        profile = profileRepository.save(profile);
        Hibernate.initialize(profile.getTools());
        log.info("Created agent profile: {} ({})", profile.getName(), profile.getId());
        return profile;
    }

    /**
     * Update an existing agent profile.
     */
    @Transactional
    public AgentProfile updateProfile(UUID id, String name, String description,
                                       List<String> capabilities, List<String> supportedTools,
                                       Set<String> toolCodes, String systemPrompt, String defaultModel) {
        AgentProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.agentProfile(id));

        // Check for name uniqueness if changed
        if (!profile.getName().equals(name) && profileRepository.existsByName(name)) {
            throw new DuplicateResourceException("AgentProfile", "name", name);
        }

        profile.setName(name);
        profile.setDescription(description);
        profile.setCapabilities(capabilities);
        profile.setSupportedTools(supportedTools);
        profile.setSystemPrompt(systemPrompt);
        profile.setDefaultModel(defaultModel);

        // Resolve and assign tools
        if (toolCodes != null) {
            Set<Tool> tools = new HashSet<>(toolRepository.findAllById(toolCodes));
            profile.setTools(tools);
        }

        profile = profileRepository.save(profile);
        Hibernate.initialize(profile.getTools());
        log.info("Updated agent profile: {} ({})", profile.getName(), profile.getId());
        return profile;
    }

    /**
     * Deactivate an agent profile (soft delete).
     */
    @Transactional
    public void deactivateProfile(UUID id) {
        AgentProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.agentProfile(id));

        // Check if any agents are using this profile
        long agentCount = agentRepository.countByProfileId(id);
        if (agentCount > 0) {
            throw ResourceInUseException.blockedBy("Agent", (int) agentCount);
        }

        profile.setStatus(AgentProfile.Status.INACTIVE);
        profileRepository.save(profile);
        log.info("Deactivated agent profile: {} ({})", profile.getName(), profile.getId());
    }

    /**
     * Reactivate an agent profile.
     */
    @Transactional
    public void activateProfile(UUID id) {
        AgentProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.agentProfile(id));

        profile.setStatus(AgentProfile.Status.ACTIVE);
        profileRepository.save(profile);
        log.info("Activated agent profile: {} ({})", profile.getName(), profile.getId());
    }

    /**
     * Delete an agent profile (hard delete).
     */
    @Transactional
    public void deleteProfile(UUID id) {
        AgentProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.agentProfile(id));

        // Check if any agents are using this profile
        long agentCount = agentRepository.countByProfileId(id);
        if (agentCount > 0) {
            throw ResourceInUseException.blockedBy("Agent", (int) agentCount);
        }

        profileRepository.delete(profile);
        log.info("Deleted agent profile: {} ({})", profile.getName(), id);
    }

    /**
     * Get agents using a profile.
     */
    @Transactional(readOnly = true)
    public List<Agent> getAgentsForProfile(UUID profileId) {
        return agentRepository.findByProfileId(profileId);
    }
}
