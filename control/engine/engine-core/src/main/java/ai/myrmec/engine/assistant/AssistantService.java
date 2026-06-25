package ai.myrmec.engine.assistant;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Parent-row service for the Assistant entity (#92,
 * {@code docs/design/assistant-entity.md} §3, §5.3, §6, §7).
 *
 * <p>Owns the cosmetic identity row (name, description), the control-plane kill
 * switches (disabled / archived — edited in place, never a version bump), and
 * the {@code assistant_grants} ACL. Version lifecycle (Draft/Publish) belongs to
 * {@link AssistantVersionService}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantService {

    private final AssistantRepository assistantRepository;
    private final AssistantGrantRepository grantRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final AssistantVersionService versionService;

    // ==================== Parent lifecycle ====================

    /**
     * Start-dialog create (§8): a new parent row plus its initial Draft seeded
     * with the chosen brain. The creator is granted OWNER.
     */
    @Transactional
    public Assistant createAssistant(UUID projectId, String name, String description,
                                     UUID agentProfileId, UUID createdBy) {
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw BadRequestException.requiredField("name");
        }
        requireUniqueName(projectId, trimmedName, null);
        requireActiveProfile(agentProfileId);

        Assistant assistant = new Assistant();
        assistant.setProjectId(projectId);
        assistant.setName(trimmedName);
        assistant.setDescription(description);
        assistant.setCreatedBy(createdBy);
        Assistant saved = assistantRepository.save(assistant);

        versionService.createInitialDraft(saved.getId(), agentProfileId, createdBy);

        if (createdBy != null) {
            grantOwner(saved.getId(), createdBy);
        }

        log.info("Created assistant {} in project {}", saved.getId(), projectId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Assistant getAssistant(UUID assistantId) {
        return requireAssistant(assistantId);
    }

    @Transactional(readOnly = true)
    public List<Assistant> listByProject(UUID projectId) {
        return assistantRepository.findByProjectIdOrderByNameAsc(projectId);
    }

    /** Zone-1 parent-row edit (§5.3): name/description in place, no version bump. */
    @Transactional
    public Assistant updateParent(UUID assistantId, String name, String description) {
        Assistant assistant = requireAssistant(assistantId);
        if (name != null) {
            String trimmedName = name.trim();
            if (trimmedName.isEmpty()) {
                throw BadRequestException.requiredField("name");
            }
            if (!Objects.equals(trimmedName, assistant.getName())) {
                requireUniqueName(assistant.getProjectId(), trimmedName, assistantId);
            }
            assistant.setName(trimmedName);
        }
        if (description != null) {
            assistant.setDescription(description);
        }
        return assistantRepository.save(assistant);
    }

    /** Reversible kill switch (§7): new sessions rejected; existing ones continue. */
    @Transactional
    public Assistant setDisabled(UUID assistantId, boolean disabled) {
        Assistant assistant = requireAssistant(assistantId);
        assistant.setDisabled(disabled);
        return assistantRepository.save(assistant);
    }

    /** Soft delete (§7): hide from listings. No hard delete in V1; reversible. */
    @Transactional
    public Assistant archive(UUID assistantId) {
        Assistant assistant = requireAssistant(assistantId);
        if (assistant.getArchivedAt() == null) {
            assistant.setArchivedAt(Instant.now());
            assistant = assistantRepository.save(assistant);
        }
        return assistant;
    }

    @Transactional
    public Assistant unarchive(UUID assistantId) {
        Assistant assistant = requireAssistant(assistantId);
        if (assistant.getArchivedAt() != null) {
            // Re-assert name uniqueness — another assistant may have taken the
            // name while this one was archived.
            requireUniqueName(assistant.getProjectId(), assistant.getName(), assistantId);
            assistant.setArchivedAt(null);
            assistant = assistantRepository.save(assistant);
        }
        return assistant;
    }

    // ==================== Grants (§6) ====================

    @Transactional(readOnly = true)
    public List<AssistantGrant> listGrants(UUID assistantId) {
        requireAssistant(assistantId);
        return grantRepository.findByAssistantId(assistantId);
    }

    @Transactional
    public AssistantGrant addGrant(UUID assistantId, AssistantGrant.PrincipalType principalType,
                                   String principalId, AssistantGrant.Permission permission, UUID grantedBy) {
        requireAssistant(assistantId);
        if (principalId == null || principalId.isBlank()) {
            throw BadRequestException.requiredField("principalId");
        }
        boolean exists = grantRepository
                .findByAssistantIdAndPrincipalTypeAndPrincipalId(assistantId, principalType, principalId)
                .stream()
                .anyMatch(g -> g.getPermission() == permission);
        if (exists) {
            throw new DuplicateResourceException("AssistantGrant", "permission", permission.name());
        }
        AssistantGrant grant = new AssistantGrant();
        grant.setAssistantId(assistantId);
        grant.setPrincipalType(principalType);
        grant.setPrincipalId(principalId);
        grant.setPermission(permission);
        grant.setGrantedBy(grantedBy);
        return grantRepository.save(grant);
    }

    @Transactional
    public void removeGrant(UUID assistantId, UUID grantId) {
        AssistantGrant grant = grantRepository.findById(grantId)
                .orElseThrow(() -> ResourceNotFoundException.of("AssistantGrant", grantId));
        if (!Objects.equals(grant.getAssistantId(), assistantId)) {
            throw ResourceNotFoundException.of("AssistantGrant", grantId);
        }
        grantRepository.delete(grant);
    }

    // ==================== Internals ====================

    private void grantOwner(UUID assistantId, UUID userId) {
        AssistantGrant grant = new AssistantGrant();
        grant.setAssistantId(assistantId);
        grant.setPrincipalType(AssistantGrant.PrincipalType.USER);
        grant.setPrincipalId(userId.toString());
        grant.setPermission(AssistantGrant.Permission.OWNER);
        grant.setGrantedBy(userId);
        grantRepository.save(grant);
    }

    private void requireUniqueName(UUID projectId, String name, UUID excludeAssistantId) {
        assistantRepository.findByProjectIdAndNameIgnoreCaseAndArchivedAtIsNull(projectId, name)
                .filter(other -> !Objects.equals(other.getId(), excludeAssistantId))
                .ifPresent(other -> {
                    throw new DuplicateResourceException("Assistant", "name", name);
                });
    }

    private void requireActiveProfile(UUID agentProfileId) {
        if (agentProfileId == null) {
            throw BadRequestException.requiredField("agentProfileId");
        }
        AgentProfile profile = agentProfileRepository.findById(agentProfileId)
                .orElseThrow(() -> ResourceNotFoundException.of("AgentProfile", agentProfileId));
        if (profile.getStatus() != AgentProfile.Status.ACTIVE) {
            throw BadRequestException.forField("agentProfileId", "INVALID_VALUE",
                    "Pick a published agent profile.");
        }
    }

    private Assistant requireAssistant(UUID assistantId) {
        return assistantRepository.findById(assistantId)
                .orElseThrow(() -> ResourceNotFoundException.of("Assistant", assistantId));
    }
}
