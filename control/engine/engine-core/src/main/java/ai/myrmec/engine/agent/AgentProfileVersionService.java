// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.tool.Tool;
import ai.myrmec.engine.tool.ToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AgentProfileVersionService (plan Feature 0, design §16.1): the Draft →
 * Publish lifecycle of an Agent Profile's immutable behaviour contract.
 *
 * <ul>
 *   <li>Create-draft-from-published: a new DRAFT copies the currently
 *       published version's content for editing.</li>
 *   <li>Publish: freezes the draft (immutable after publish), archives the
 *       previously published version, and assigns the next monotonic
 *       version number. Exactly one PUBLISHED version exists per profile.</li>
 *   <li>getPublishedVersion: the runtime resolution API — SessionContext
 *       assembly, instance reservation, and workflow publication all read
 *       the published version row, never the mutable draft.</li>
 * </ul>
 *
 * Published content is never edited in place: editing a published field
 * requires a new draft → publish cycle. The platform is in development
 * phase; seeded profiles are created with an already-published version 1.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentProfileVersionService {

    private final AgentProfileVersionRepository versionRepository;
    private final AgentProfileRepository profileRepository;
    private final ToolRepository toolRepository;

    /**
     * The first publish: create a profile's version 1 directly in the
     * PUBLISHED state with the given content (plan Feature 0: "seed data
     * publishes version 1"; used by both the seed changelog semantics and
     * AgentProfileService.createProfile in the dev phase).
     */
    @Transactional
    public AgentProfileVersion publishInitial(
            UUID profileId,
            List<String> capabilities,
            Set<String> toolCodes,
            String systemPrompt,
            String defaultModel,
            AgentProfileVersion.InteractionMode interactionMode) {
        return publishInitial(profileId, capabilities, toolCodes, systemPrompt,
                defaultModel, interactionMode, null, null, null);
    }

    /**
     * The first publish with orchestration policy content (§7/§17.4): the
     * version's command templates + approval policy ride the same
     * immutable published version; serialized as canonical JSON maps.
     */
    @Transactional
    public AgentProfileVersion publishInitial(
            UUID profileId,
            List<String> capabilities,
            Set<String> toolCodes,
            String systemPrompt,
            String defaultModel,
            AgentProfileVersion.InteractionMode interactionMode,
            java.util.Map<String, Object> commandTemplates,
            java.util.Map<String, Object> approvalPolicy,
            Integer approvalRequestTtlSeconds) {
        profileRepository.findById(profileId)
                .orElseThrow(() -> ResourceNotFoundException.agentProfile(profileId));

        AgentProfileVersion version = new AgentProfileVersion();
        version.setProfileId(profileId);
        version.setVersionNumber(1);
        version.setCapabilities(capabilities == null ? List.of() : capabilities);
        version.setSystemPrompt(systemPrompt);
        version.setDefaultModel(defaultModel);
        version.setInteractionMode(
                interactionMode == null ? AgentProfileVersion.InteractionMode.ONE_SHOT : interactionMode);
        version.setCommandTemplates(writeJsonMap(commandTemplates));
        version.setApprovalPolicy(writeJsonMap(approvalPolicy));
        version.setApprovalRequestTtlSeconds(approvalRequestTtlSeconds);
        version.setStatus(AgentProfileVersion.Status.PUBLISHED);
        version.setPublishedAt(Instant.now());
        version = versionRepository.save(version);
        assignTools(version, toolCodes);
        log.info("Published initial agent-profile version v1 for profile {}", profileId);
        return version;
    }

    private String writeJsonMap(java.util.Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid orchestration policy map", e);
        }
    }

    /**
     * Create a DRAFT copied from the currently published version. Only one
     * open draft per profile; re-requesting the open draft returns it
     * unchanged (idempotent, mirrors the AssistantVersion open-draft rule).
     */
    @Transactional
    public AgentProfileVersion createDraft(UUID profileId) {
        Optional<AgentProfileVersion> openDraft =
                versionRepository.findByProfileIdAndStatus(profileId, AgentProfileVersion.Status.DRAFT);
        if (openDraft.isPresent()) {
            return openDraft.get();
        }
        AgentProfileVersion published = requirePublished(profileId);
        AgentProfileVersion draft = copyContent(published);
        draft.setProfileId(profileId);
        draft.setVersionNumber(nextVersionNumber(profileId));
        draft.setStatus(AgentProfileVersion.Status.DRAFT);
        draft = versionRepository.save(draft);
        assignTools(draft, toolCodesOf(published));
        log.info("Opened draft v{} for agent profile {}", draft.getVersionNumber(), profileId);
        return draft;
    }

    /**
     * Edit the open draft's content. Editing a published version requires
     * the draft → publish cycle — this method rejects non-DRAFT rows.
     */
    @Transactional
    public AgentProfileVersion updateDraft(
            UUID versionId,
            List<String> capabilities,
            Set<String> toolCodes,
            String systemPrompt,
            String defaultModel,
            AgentProfileVersion.InteractionMode interactionMode) {
        AgentProfileVersion draft = versionRepository.findById(versionId)
                .orElseThrow(() -> ResourceNotFoundException.of("AgentProfileVersion", versionId));
        if (draft.getStatus() != AgentProfileVersion.Status.DRAFT) {
            throw new BadRequestException(
                    "Published profile versions are immutable. Open a new draft to edit this profile.");
        }
        draft.setCapabilities(capabilities == null ? List.of() : capabilities);
        draft.setSystemPrompt(systemPrompt);
        draft.setDefaultModel(defaultModel);
        draft.setInteractionMode(
                interactionMode == null ? AgentProfileVersion.InteractionMode.ONE_SHOT : interactionMode);
        AgentProfileVersion saved = versionRepository.save(draft);
        if (toolCodes != null) {
            assignTools(saved, toolCodes);
        }
        return saved;
    }

    /**
     * Publish the open draft: freeze it, archive the previously published
     * version, stamp the publish metadata. No-op publish (content identical
     * to the published version) is rejected — mirrors the AssistantVersion
     * rule and keeps version history meaningful.
     */
    @Transactional
    public AgentProfileVersion publish(UUID profileId, UUID publisherId) {
        // Lock the parent row so concurrent publishes serialize (§5.6
        // analogue of the AssistantVersion flip).
        profileRepository.findById(profileId)
                .orElseThrow(() -> ResourceNotFoundException.agentProfile(profileId));

        AgentProfileVersion draft = versionRepository
                .findByProfileIdAndStatus(profileId, AgentProfileVersion.Status.DRAFT)
                .orElseThrow(() -> new BadRequestException(
                        "No open draft to publish. Open a draft first."));

        AgentProfileVersion published = versionRepository
                .findByProfileIdAndStatus(profileId, AgentProfileVersion.Status.PUBLISHED)
                .orElse(null);

        if (published != null && contentEquals(draft, published, toolCodesOf(draft), toolCodesOf(published))) {
            throw new BadRequestException("No changes to publish.");
        }

        // Freeze: the draft becomes the single published version; the old
        // one is archived (immutable history).
        if (published != null) {
            published.setStatus(AgentProfileVersion.Status.ARCHIVED);
            versionRepository.save(published);
        }
        draft.setStatus(AgentProfileVersion.Status.PUBLISHED);
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(publisherId);
        AgentProfileVersion result = versionRepository.save(draft);
        log.info("Published agent profile {} version v{}",
                profileId, result.getVersionNumber());
        return result;
    }

    /**
     * The runtime resolution API: the one published version of a profile.
     * Callers that need a published version to exist (session assembly,
     * instance reservation, workflow publication) use this form.
     */
    @Transactional(readOnly = true)
    public AgentProfileVersion requirePublished(UUID profileId) {
        return versionRepository
                .findByProfileIdAndStatus(profileId, AgentProfileVersion.Status.PUBLISHED)
                .orElseThrow(() -> new BadRequestException(
                        "Agent profile has no published version. Publish a version before using it."));
    }

    /** Optional form for lookups that tolerate an unpublished profile. */
    @Transactional(readOnly = true)
    public Optional<AgentProfileVersion> findPublished(UUID profileId) {
        return versionRepository.findByProfileIdAndStatus(profileId, AgentProfileVersion.Status.PUBLISHED);
    }

    /**
     * The published version with its tools initialized inside the
     * transaction — safe for response assembly once the session closes
     * (open-in-view off): the lazy @ManyToMany collection is JOIN FETCHed
     * before the read-only transaction commits.
     */
    @Transactional(readOnly = true)
    public Optional<AgentProfileVersion> findPublishedWithTools(UUID profileId) {
        return versionRepository.findPublishedWithTools(
                profileId, AgentProfileVersion.Status.PUBLISHED);
    }

    /** All versions of a profile, newest first. */
    @Transactional(readOnly = true)
    public List<AgentProfileVersion> listVersions(UUID profileId) {
        return versionRepository.findByProfileIdOrderByVersionNumberDesc(profileId);
    }

    // ── Draft/Publish lifecycle surface (§16.1, the UI Draft/Publish
    //    actions' engine API — mirrors the AssistantVersion pattern) ──

    /** The single open DRAFT — throws when none exists (404-mapped). */
    @Transactional(readOnly = true)
    public AgentProfileVersion getOpenDraft(UUID profileId) {
        return versionRepository
                .findByProfileIdAndStatus(profileId, AgentProfileVersion.Status.DRAFT)
                .orElseThrow(() -> ResourceNotFoundException.of(
                        "AgentProfileVersion", "open draft for profile " + profileId));
    }

    /** Optional form for the UI's draft banner (no throw when absent). */
    @Transactional(readOnly = true)
    public Optional<AgentProfileVersion> findOpenDraft(UUID profileId) {
        return versionRepository.findByProfileIdAndStatus(profileId, AgentProfileVersion.Status.DRAFT);
    }

    /** Discard the open draft — frees the single-draft slot; the
     * published version (if any) stays untouched. */
    @Transactional
    public void discardDraft(UUID profileId) {
        AgentProfileVersion draft = versionRepository
                .findByProfileIdAndStatus(profileId, AgentProfileVersion.Status.DRAFT)
                .orElseThrow(() -> ResourceNotFoundException.of(
                        "AgentProfileVersion", "open draft for profile " + profileId));
        versionRepository.delete(draft);
        log.info("Discarded open draft v{} for agent profile {}",
                draft.getVersionNumber(), profileId);
    }

    // ── internals ──────────────────────────────────────────────

    private int nextVersionNumber(UUID profileId) {
        return versionRepository.findMaxVersionNumber(profileId).orElse(0) + 1;
    }

    private AgentProfileVersion copyContent(AgentProfileVersion source) {
        AgentProfileVersion copy = new AgentProfileVersion();
        copy.setCapabilities(source.getCapabilities());
        copy.setSystemPrompt(source.getSystemPrompt());
        copy.setDefaultModel(source.getDefaultModel());
        copy.setInteractionMode(source.getInteractionMode());
        copy.setCommandTemplates(source.getCommandTemplates());
        copy.setApprovalPolicy(source.getApprovalPolicy());
        copy.setRequiredIsolation(source.getRequiredIsolation());
        copy.setGitPolicy(source.getGitPolicy());
        copy.setWorkspaceRetentionSeconds(source.getWorkspaceRetentionSeconds());
        return copy;
    }

    private void assignTools(AgentProfileVersion version, Set<String> toolCodes) {
        if (toolCodes == null) {
            return;
        }
        Set<Tool> tools = new HashSet<>(toolRepository.findAllById(toolCodes));
        version.setTools(tools);
        versionRepository.save(version);
    }

    private Set<String> toolCodesOf(AgentProfileVersion version) {
        Set<String> codes = new HashSet<>();
        if (version.getTools() != null) {
            version.getTools().forEach(t -> codes.add(t.getCode()));
        }
        return codes;
    }

    private boolean contentEquals(
            AgentProfileVersion a, AgentProfileVersion b,
            Set<String> toolsA, Set<String> toolsB) {
        return Objects.equals(a.getSystemPrompt(), b.getSystemPrompt())
                && Objects.equals(a.getDefaultModel(), b.getDefaultModel())
                && Objects.equals(a.getCapabilities(), b.getCapabilities())
                && Objects.equals(a.getInteractionMode(), b.getInteractionMode())
                && Objects.equals(toolsA, toolsB);
    }
}