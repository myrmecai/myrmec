package ai.myrmec.engine.assistant;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DraftConflictException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine._system.exception.ValidationDetail;
import ai.myrmec.engine.knowledge.KnowledgeProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Version-lifecycle service for the Assistant entity (#92,
 * {@code docs/design/assistant-entity.md} §5).
 *
 * <p>Owns Draft creation/clone, the single-Draft invariant (§5.4), take-over
 * (§5.4), discard, and the publish gate (§5.1) including stale-Draft detection
 * (§5.5), bump classification + version-number assignment (§5.3), and the
 * row-locked {@code current_version_id} flip (§5.6). Parent-row concerns live
 * in {@link AssistantService}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantVersionService {

    /** Channels a version may be reachable through (§4.6). */
    private static final Set<String> ALLOWED_CHANNELS = Set.of("WEB_UI", "EXTERNAL_API");

    private final AssistantVersionRepository versionRepository;
    private final AssistantRepository assistantRepository;
    private final KnowledgeProviderRepository knowledgeProviderRepository;
    private final AssistantAgentBindingRepository assistantAgentBindingRepository;
    private final ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService;

    // ==================== Draft creation ====================

    /**
     * Create the initial Draft for a freshly-created Assistant (start-dialog
     * path). No clone source — seeded with defaults plus the chosen brain.
     */
    @Transactional
    public AssistantVersion createInitialDraft(UUID assistantId, UUID ownerId) {
        AssistantVersion draft = new AssistantVersion();
        draft.setAssistantId(assistantId);
        draft.setStatus(AssistantVersion.Status.DRAFT);
        draft.setDraftOwnerId(ownerId);
        // parentVersionId stays null: nothing was published before this.
        return versionRepository.save(draft);
    }

    /**
     * Open a new Draft by cloning the currently-Published version (§5.4).
     * Enforces the single-Draft invariant: 409 if one is already open.
     */
    @Transactional
    public AssistantVersion openDraft(UUID assistantId, UUID ownerId) {
        Assistant assistant = requireAssistant(assistantId);

        versionRepository.findByAssistantIdAndStatus(assistantId, AssistantVersion.Status.DRAFT)
                .ifPresent(existing -> {
                    throw DraftConflictException.singleDraft(
                            existing.getId(), existing.getDraftOwnerId(), existing.getCreatedAt());
                });

        UUID currentVersionId = assistant.getCurrentVersionId();
        if (currentVersionId == null) {
            throw new BadRequestException(
                    "This assistant has no published version yet; edit the existing draft instead.");
        }
        AssistantVersion source = versionRepository.findById(currentVersionId)
                .orElseThrow(() -> ResourceNotFoundException.of("AssistantVersion", currentVersionId));

        AssistantVersion draft = cloneForDraft(source);
        draft.setDraftOwnerId(ownerId);
        draft.setParentVersionId(currentVersionId);
        draft = versionRepository.save(draft);
        cloneBindingsForDraft(currentVersionId, draft.getId());
        return draft;
    }

    /**
     * Persist edits to an open Draft (Zone-2 fields). Rejects any attempt to
     * edit a Published (frozen) row.
     */
    @Transactional
    public AssistantVersion saveDraft(AssistantVersion draft) {
        if (draft.getStatus() != AssistantVersion.Status.DRAFT) {
            throw new BadRequestException("Only draft versions can be edited.");
        }
        return versionRepository.save(draft);
    }

    /** Discard an open Draft, freeing the single-Draft slot (§5.4). */
    @Transactional
    public void discardDraft(UUID assistantId) {
        AssistantVersion draft = requireDraft(assistantId);
        versionRepository.delete(draft);
    }

    /**
     * Reassign the Draft to a new owner (§5.4). OWNER may take over from anyone;
     * EDITOR may take over only their own Draft — that authorization decision is
     * made by the caller (controller/ACL) and passed as {@code allowAnyOwner}.
     */
    @Transactional
    public AssistantVersion takeOverDraft(UUID assistantId, UUID newOwnerId, boolean allowAnyOwner) {
        AssistantVersion draft = requireDraft(assistantId);
        if (!allowAnyOwner && !Objects.equals(draft.getDraftOwnerId(), newOwnerId)) {
            throw new BadRequestException("You may only take over your own draft.");
        }
        draft.setDraftOwnerId(newOwnerId);
        return versionRepository.save(draft);
    }

    // ==================== Publish ====================

    /**
     * Promote the open Draft to Published (§5.1). Validates the full publish
     * gate (collecting <em>all</em> failures), rejects stale Drafts (§5.5) and
     * no-op re-publishes (§5.2), assigns the version number from the computed
     * bump type (§5.3), and flips {@code current_version_id} under a row lock on
     * the parent (§5.6).
     */
    @Transactional
    public AssistantVersion publish(UUID assistantId, UUID publisherId) {
        // Row lock the parent before we read current_version_id / flip it (§5.6).
        Assistant assistant = assistantRepository.findByIdForUpdate(assistantId)
                .orElseThrow(() -> ResourceNotFoundException.of("Assistant", assistantId));

        AssistantVersion draft = requireDraft(assistantId);

        // Stale-Draft gate (§5.5): the version we forked from must still be current.
        if (!Objects.equals(draft.getParentVersionId(), assistant.getCurrentVersionId())) {
            AssistantVersion current = assistant.getCurrentVersionId() == null ? null
                    : versionRepository.findById(assistant.getCurrentVersionId()).orElse(null);
            String currentNumber = current != null ? current.getVersionNumber() : "?";
            throw DraftConflictException.staleDraft(currentNumber);
        }

        AssistantVersion previous = assistant.getCurrentVersionId() == null ? null
                : versionRepository.findById(assistant.getCurrentVersionId()).orElse(null);

        // Publish gate — collect ALL failures, then a 400 highlighting every field.
        List<ValidationDetail> failures = validatePublishGate(assistant, draft);
        if (!failures.isEmpty()) {
            throw new BadRequestException("This assistant cannot be published yet.", failures);
        }

        // TODO(multi-model-bindings): profile version stamping moved to
        // assistant_agent_bindings. The primary binding's profile_version_id
        // is resolved separately; do not stamp the (now-removed) version-row
        // agent_profile_version_id here.

        // No-op re-publish (§5.2) — only meaningful once a baseline exists.
        if (previous != null && !versionRowDiffers(draft, previous)) {
            throw new BadRequestException("No changes to publish.");
        }

        AssistantVersion.BumpType bump = computeBumpType(draft, previous);
        draft.setBumpType(bump);
        draft.setVersionNumber(nextVersionNumber(previous, bump));
        draft.setStatus(AssistantVersion.Status.PUBLISHED);
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(publisherId);
        AssistantVersion published = versionRepository.save(draft);
        stampProfileVersions(published.getId());

        assistant.setCurrentVersionId(published.getId());
        assistantRepository.save(assistant);

        log.info("Published assistant {} version {} ({})",
                assistantId, published.getVersionNumber(), bump);
        return published;
    }

    // ==================== Reads ====================

    @Transactional(readOnly = true)
    public List<AssistantVersion> listVersions(UUID assistantId) {
        return versionRepository.findByAssistantIdOrderByCreatedAtDesc(assistantId);
    }

    /** Fetch the single open Draft, or 400 if none exists. */
    @Transactional(readOnly = true)
    public AssistantVersion getOpenDraft(UUID assistantId) {
        return requireDraft(assistantId);
    }

    // ==================== Publish-gate rules ====================

    private List<ValidationDetail> validatePublishGate(Assistant assistant, AssistantVersion draft) {
        List<ValidationDetail> failures = new ArrayList<>();

        // name: non-empty, unique per Team (case-insensitive). Lives on the parent.
        String name = assistant.getName();
        if (name == null || name.isBlank()) {
            failures.add(ValidationDetail.of("name", "REQUIRED",
                    "Name is required and must be unique in this Team."));
        } else {
            assistantRepository
                    .findByTeamIdAndNameIgnoreCaseAndArchivedAtIsNull(assistant.getTeamId(), name)
                    .filter(other -> !Objects.equals(other.getId(), assistant.getId()))
                    .ifPresent(other -> failures.add(ValidationDetail.of("name", "DUPLICATE",
                            "Name is required and must be unique in this Team.")));
        }

        // brain: profile binding validation is deferred to the multi-model
        // bindings refactor (assistant_agent_bindings). The version row no
        // longer carries agent_profile_id / agent_profile_version_id.
        // TODO(multi-model-bindings): validate the primary binding's profile
        // version + addendum opt-in once bindings are wired.

        // reach: non-empty subset of allowed channels.
        List<String> channels = draft.getUsableVia();
        if (channels == null || channels.isEmpty()) {
            failures.add(ValidationDetail.of("usableVia", "REQUIRED", "Pick at least one channel."));
        } else {
            channels.stream()
                    .filter(c -> !ALLOWED_CHANNELS.contains(c))
                    .forEach(c -> failures.add(ValidationDetail.of("usableVia", "INVALID_VALUE",
                            "Unsupported channel: " + c + ".")));
        }

        // knowledge: validate that kbBindings reference existing knowledge providers.
        List<String> unavailableKbs = new ArrayList<>();
        if (draft.getKbBindings() != null) {
            for (String kbId : draft.getKbBindings()) {
                try {
                    UUID id = UUID.fromString(kbId);
                    if (!knowledgeProviderRepository.existsById(id)) {
                        unavailableKbs.add(kbId);
                    }
                } catch (IllegalArgumentException e) {
                    unavailableKbs.add(kbId);
                }
            }
        }
        if (!unavailableKbs.isEmpty()) {
            failures.add(ValidationDetail.of("kbBindings", "INVALID_VALUE",
                    "One or more selected knowledge providers are unavailable: "
                            + String.join(", ", unavailableKbs) + "."));
        }

        // tools: disabled-tool validation is deferred to the multi-model
        // bindings refactor — the profile tool set is now reachable via the
        // primary binding's profile version, not a version-row field.
        // TODO(multi-model-bindings): re-add disabled-tool membership check
        // against the primary binding's published profile version.

        return failures;
    }

    // ==================== Bump / version helpers ====================

    /**
     * Classify the bump (§5.3): brain swap or a new STRICT HITL override is a
     * MAJOR (in-session notice); everything else is a MINOR. First publish is a
     * MAJOR (the birth of v1.0).
     */
    private AssistantVersion.BumpType computeBumpType(AssistantVersion draft, AssistantVersion previous) {
        if (previous == null) {
            return AssistantVersion.BumpType.MAJOR;
        }
        // TODO(multi-model-bindings): brain-swap detection moves to comparing the
        // assistant_agent_bindings set (primary/subagent profile versions), not a
        // version-row field.
        boolean hitlTightened = draft.getHitlOverrideMode() == AssistantVersion.HitlOverrideMode.STRICT
                && previous.getHitlOverrideMode() != AssistantVersion.HitlOverrideMode.STRICT;
        return hitlTightened
                ? AssistantVersion.BumpType.MAJOR
                : AssistantVersion.BumpType.MINOR;
    }

    /** Two-part semver: MAJOR → (m+1).0, MINOR/PATCH → m.(n+1). First publish → 1.0. */
    private String nextVersionNumber(AssistantVersion previous, AssistantVersion.BumpType bump) {
        if (previous == null || previous.getVersionNumber() == null) {
            return "1.0";
        }
        String[] parts = previous.getVersionNumber().split("\\.");
        int major = parts.length > 0 ? parseIntSafe(parts[0]) : 1;
        int minor = parts.length > 1 ? parseIntSafe(parts[1]) : 0;
        if (bump == AssistantVersion.BumpType.MAJOR) {
            return (major + 1) + ".0";
        }
        return major + "." + (minor + 1);
    }

    /** Whether any version-row field differs from the baseline (§5.2 diff). */
    private boolean versionRowDiffers(AssistantVersion a, AssistantVersion b) {
        return !Objects.equals(a.getAddendum(), b.getAddendum())
                || !Objects.equals(a.getGreetingMessage(), b.getGreetingMessage())
                || a.getMaxIdleMinutes() != b.getMaxIdleMinutes()
                || !Objects.equals(a.getMaxSessionAgeHours(), b.getMaxSessionAgeHours())
                || !Objects.equals(a.getKbBindings(), b.getKbBindings())
                || !Objects.equals(a.getDisabledTools(), b.getDisabledTools())
                || a.getHitlOverrideMode() != b.getHitlOverrideMode()
                || !Objects.equals(a.getUsableVia(), b.getUsableVia())
                || a.isAttachmentsEnabled() != b.isAttachmentsEnabled()
                || !Objects.equals(a.getAttachmentRetentionTtl(), b.getAttachmentRetentionTtl())
                || !Objects.equals(a.getAttachmentMaxFileSize(), b.getAttachmentMaxFileSize())
                || !Objects.equals(a.getAttachmentTypeAllowlist(), b.getAttachmentTypeAllowlist());
    }

    /** Copy every version-row field from a published source into a fresh Draft. */
    private AssistantVersion cloneForDraft(AssistantVersion source) {
        AssistantVersion draft = new AssistantVersion();
        draft.setAssistantId(source.getAssistantId());
        draft.setStatus(AssistantVersion.Status.DRAFT);
        draft.setAddendum(source.getAddendum());
        draft.setGreetingMessage(source.getGreetingMessage());
        draft.setMaxIdleMinutes(source.getMaxIdleMinutes());
        draft.setMaxSessionAgeHours(source.getMaxSessionAgeHours());
        draft.setKbBindings(new ArrayList<>(source.getKbBindings()));
        draft.setDisabledTools(new ArrayList<>(source.getDisabledTools()));
        draft.setHitlOverrideMode(source.getHitlOverrideMode());
        draft.setUsableVia(new ArrayList<>(source.getUsableVia()));
        draft.setAttachmentsEnabled(source.isAttachmentsEnabled());
        draft.setAttachmentRetentionTtl(source.getAttachmentRetentionTtl());
        draft.setAttachmentMaxFileSize(source.getAttachmentMaxFileSize());
        draft.setAttachmentTypeAllowlist(source.getAttachmentTypeAllowlist());
        return draft;
    }

    private AssistantVersion requireDraft(UUID assistantId) {
        return versionRepository.findByAssistantIdAndStatus(assistantId, AssistantVersion.Status.DRAFT)
                .orElseThrow(() -> new BadRequestException("This assistant has no open draft."));
    }

    private Assistant requireAssistant(UUID assistantId) {
        return assistantRepository.findById(assistantId)
                .orElseThrow(() -> ResourceNotFoundException.of("Assistant", assistantId));
    }

    /**
     * Freeze the profile ensemble at publish: for every binding on this
     * (now-published) version, resolve the profile's current published version
     * and stamp it on the binding. Once stamped, the binding row is immutable
     * alongside its published assistant version — the profile can republish a
     * newer version without changing this assistant version's behaviour.
     */
    private void stampProfileVersions(UUID assistantVersionId) {
        for (AssistantAgentBinding binding
                : assistantAgentBindingRepository.findByAssistantVersionIdOrderByRoleTypeAsc(assistantVersionId)) {
            if (binding.getAgentProfileVersionId() == null) {
                var version = agentProfileVersionService.requirePublished(binding.getAgentProfileId());
                binding.setAgentProfileVersionId(version.getId());
                assistantAgentBindingRepository.save(binding);
            }
        }
    }

    /**
     * Copy the parent's role bindings onto the new draft (re-pointed to the
     * draft id). The {@code agentProfileVersionId} is deliberately NOT copied —
     * the draft re-resolves "latest published" at its own publish time.
     */
    private void cloneBindingsForDraft(UUID sourceVersionId, UUID draftVersionId) {
        for (AssistantAgentBinding src
                : assistantAgentBindingRepository.findByAssistantVersionIdOrderByRoleTypeAsc(sourceVersionId)) {
            AssistantAgentBinding copy = new AssistantAgentBinding();
            copy.setAssistantVersionId(draftVersionId);
            copy.setAgentProfileId(src.getAgentProfileId());
            copy.setModelCode(src.getModelCode());
            copy.setRoleType(src.getRoleType());
            copy.setAgentAlias(src.getAgentAlias());
            copy.setPromptAddendum(src.getPromptAddendum());
            assistantAgentBindingRepository.save(copy);
        }
    }

    private static java.util.Optional<UUID> parseUuid(String value) {
        try {
            return java.util.Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
