// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceInUseException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent Profile administration (design §16.1). Zone 1 (identity/admin)
 * is managed here; every Zone 2 behaviour edit goes through the
 * {@link AgentProfileVersionService} Draft → Publish cycle.
 *
 * <p>Dev-phase API semantics: {@link #createProfile} publishes an
 * initial version 1 with the supplied behaviour content (the plan's
 * "seed data publishes version 1" rule generalized to runtime creation);
 * {@link #updateProfile} updates Zone 1 directly and runs a draft →
 * publish cycle only when the Zone 2 content actually differs from the
 * published version — a Zone-1-only rename never creates a spurious
 * version.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentProfileService {

    private final AgentProfileRepository profileRepository;
    private final AgentProfileVersionService versionService;

    /**
     * Get all agent profiles.
     */
    @Transactional(readOnly = true)
    public List<AgentProfile> getAllProfiles() {
        return profileRepository.findAll();
    }

    /**
     * Get all active agent profiles.
     */
    @Transactional(readOnly = true)
    public List<AgentProfile> getActiveProfiles() {
        return profileRepository.findByStatus(AgentProfile.Status.ACTIVE);
    }

    /**
     * Get a profile by ID.
     */
    @Transactional(readOnly = true)
    public AgentProfile getProfile(UUID id) {
        return profileRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.agentProfile(id));
    }

    /**
     * Create a new agent profile: Zone 1 identity row plus a published
     * version 1 carrying the behaviour contract (dev phase — plan
     * Feature 0: "seed data publishes version 1").
     *
     * @param supportedTools deprecated legacy parameter — accepted for
     *                      API compatibility, ignored (the canonical tool
     *                      set is the version's toolCodes)
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
        profile.setStatus(AgentProfile.Status.ACTIVE);
        profile = profileRepository.save(profile);

        versionService.publishInitial(
                profile.getId(), capabilities, toolCodes,
                systemPrompt, defaultModel, AgentProfileVersion.InteractionMode.ONE_SHOT);

        log.info("Created agent profile: {} ({}) with published version 1",
                profile.getName(), profile.getId());
        return profile;
    }

    /**
     * Create with orchestration policy content (§7/§17.4): the published
     * version 1 carries the command templates + approval policy the
     * assignment assembler projects into the ExecutionPolicy block, plus
     * the §17.4 approval-request TTL that bounds the decision window.
     */
    @Transactional
    public AgentProfile createProfile(String name, String description,
                                       List<String> capabilities, Set<String> toolCodes,
                                       String systemPrompt, String defaultModel,
                                       java.util.Map<String, Object> commandTemplates,
                                       java.util.Map<String, Object> approvalPolicy,
                                       Integer approvalRequestTtlSeconds) {
        if (profileRepository.existsByName(name)) {
            throw new DuplicateResourceException("AgentProfile", "name", name);
        }
        AgentProfile profile = new AgentProfile();
        profile.setName(name);
        profile.setDescription(description);
        profile.setStatus(AgentProfile.Status.ACTIVE);
        profile = profileRepository.save(profile);

        versionService.publishInitial(profile.getId(), capabilities, toolCodes,
                systemPrompt, defaultModel,
                AgentProfileVersion.InteractionMode.ONE_SHOT,
                commandTemplates, approvalPolicy, approvalRequestTtlSeconds);
        log.info("Created agent profile: {} ({}) with published version 1 "
                        + "(orchestration policy attached)",
                profile.getName(), profile.getId());
        return profile;
    }

    /**
     * Update an existing agent profile. Zone 1 fields (name, description)
     * update directly. Zone 2 fields (capabilities, tools, system prompt,
     * default model) run the draft → publish cycle — editing a published
     * field requires a new version (design §16.1); an identical-content
     * update publishes nothing.
     *
     * @param supportedTools deprecated legacy parameter — accepted for
     *                      API compatibility, ignored
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
        profileRepository.save(profile);

        // Zone 2: draft → publish only when the behaviour contract changes.
        Optional<AgentProfileVersion> published = versionService.findPublished(id);
        if (published.isEmpty()) {
            // No published version yet (edge case): publish v1 with the
            // supplied content.
            versionService.publishInitial(
                    id, capabilities, toolCodes, systemPrompt, defaultModel,
                    AgentProfileVersion.InteractionMode.ONE_SHOT);
        } else {
            AgentProfileVersion current = published.get();
            boolean behaviourChanged = !Objects.equals(current.getCapabilities(), capabilities)
                    || !Objects.equals(current.getSystemPrompt(), systemPrompt)
                    || !Objects.equals(current.getDefaultModel(), defaultModel)
                    || (toolCodes != null && !Objects.equals(toolCodesOf(current), toolCodes));
            if (behaviourChanged) {
                AgentProfileVersion draft = versionService.createDraft(id);
                versionService.updateDraft(
                        draft.getId(), capabilities, toolCodes,
                        systemPrompt, defaultModel,
                        current.getInteractionMode());
                versionService.publish(id, null);
            }
        }

        log.info("Updated agent profile: {} ({})", profile.getName(), profile.getId());
        return profile;
    }

    /**
     * Deactivate an agent profile (soft delete).
     *
     * <p>Since the host-profile decoupling (D2), agent hosts no longer carry
     * a {@code profileId}. Deactivation/ deletion of a profile is therefore
     * no longer blocked by host bindings; any running instances are bound at
     * session time through {@link Agent#getProfileVersionId()}.</p>
     */
    @Transactional
    public void deactivateProfile(UUID id) {
        AgentProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.agentProfile(id));

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
     * Delete an agent profile (hard delete). Versions cascade.
     *
     * <p>Host-profile bindings were dropped by the D2 decoupling; there is no
     * need to check host references before deletion.</p>
     */
    @Transactional
    public void deleteProfile(UUID id) {
        AgentProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.agentProfile(id));

        profileRepository.delete(profile);
        log.info("Deleted agent profile: {} ({})", profile.getName(), id);
    }

    private Set<String> toolCodesOf(AgentProfileVersion version) {
        return version.getTools() == null
                ? Set.of()
                : version.getTools().stream().map(Tool::getCode).collect(Collectors.toSet());
    }
}
