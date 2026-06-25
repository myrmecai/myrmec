package ai.myrmec.engine.external;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantGrant;
import ai.myrmec.engine.assistant.AssistantGrantRepository;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.assistant.AssistantVersion;
import ai.myrmec.engine.assistant.AssistantVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves which assistants an External-API ({@code /api/v1/external/**})
 * service account may use, enforcing the #95 <b>double gate</b>:
 *
 * <ol>
 *   <li><b>Grant gate</b> — an {@link AssistantGrant} of permission
 *       {@code USE} for {@code (SERVICE_ACCOUNT, serviceAccountId)} on the
 *       assistant; and</li>
 *   <li><b>Reach gate</b> — the assistant's currently published
 *       {@link AssistantVersion} lists {@code EXTERNAL_API} in its
 *       {@code usableVia} set.</li>
 * </ol>
 *
 * <p>Any failure of either gate (plus not-found, wrong project, archived,
 * disabled, or unpublished) is reported uniformly as a 404
 * {@code RESOURCE_NOT_FOUND} so an external caller cannot enumerate or probe
 * assistants it has no access to.</p>
 */
@Service
@RequiredArgsConstructor
public class ExternalAssistantService {

    /** The {@code usable_via} channel token that opens an assistant to this API. */
    public static final String EXTERNAL_API_CHANNEL = "EXTERNAL_API";

    private final AssistantRepository assistantRepository;
    private final AssistantVersionRepository assistantVersionRepository;
    private final AssistantGrantRepository grantRepository;

    /** An assistant paired with the published version that satisfies the reach gate. */
    public record UsableAssistant(Assistant assistant, AssistantVersion version) {
    }

    /**
     * Every assistant in the service account's project that satisfies the
     * double gate, ordered by name. Used by {@code GET /external/assistants}.
     */
    @Transactional(readOnly = true)
    public List<UsableAssistant> listUsable(UUID serviceAccountId, UUID projectId) {
        return assistantRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(assistant -> toUsable(serviceAccountId, assistant))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Resolve one assistant for this service account, enforcing the double
     * gate. Throws 404 when the assistant is missing or fails either gate.
     */
    @Transactional(readOnly = true)
    public UsableAssistant requireUsable(UUID serviceAccountId, UUID projectId, UUID assistantId) {
        Assistant assistant = assistantId == null
                ? null
                : assistantRepository.findById(assistantId).orElse(null);
        if (assistant == null) {
            throw notFound(assistantId);
        }
        return toUsable(serviceAccountId, assistant)
                .filter(u -> projectId == null || projectId.equals(assistant.getProjectId()))
                .orElseThrow(() -> notFound(assistantId));
    }

    /**
     * Apply the double gate to one assistant, returning the usable pairing or
     * empty. Centralises the predicate shared by listing and single resolve.
     */
    private Optional<UsableAssistant> toUsable(UUID serviceAccountId, Assistant assistant) {
        if (assistant.getArchivedAt() != null || assistant.isDisabled()) {
            return Optional.empty();
        }
        UUID versionId = assistant.getCurrentVersionId();
        if (versionId == null) {
            return Optional.empty();
        }
        AssistantVersion version = assistantVersionRepository.findById(versionId).orElse(null);
        if (version == null || !version.getUsableVia().contains(EXTERNAL_API_CHANNEL)) {
            return Optional.empty();
        }
        if (!hasUseGrant(assistant.getId(), serviceAccountId)) {
            return Optional.empty();
        }
        return Optional.of(new UsableAssistant(assistant, version));
    }

    private boolean hasUseGrant(UUID assistantId, UUID serviceAccountId) {
        return grantRepository
                .findByAssistantIdAndPrincipalTypeAndPrincipalId(
                        assistantId,
                        AssistantGrant.PrincipalType.SERVICE_ACCOUNT,
                        serviceAccountId.toString())
                .stream()
                .anyMatch(g -> g.getPermission() == AssistantGrant.Permission.USE);
    }

    private static ResourceNotFoundException notFound(UUID assistantId) {
        return ResourceNotFoundException.of("Assistant", String.valueOf(assistantId));
    }
}
