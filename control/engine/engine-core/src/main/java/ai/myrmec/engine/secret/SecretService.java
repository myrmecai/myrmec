package ai.myrmec.engine.secret;

import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceInUseException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectKnowledgeRepoRepository;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.secret.dto.CreateSecretRequest;
import ai.myrmec.engine.secret.dto.SecretResponse;
import ai.myrmec.engine.secret.dto.UpdateSecretRequest;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Write-side service for secrets — shared by the project-scoped controller and
 * the SYSTEM_ADMIN global controller. Authorization happens at the controller
 * layer via {@code @PreAuthorize}; this service trusts its caller.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecretService {

    private final SecretRepository secretRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final SecretBackendRegistry backendRegistry;
    private final LocalSecretBackendAdapter localBackend;
    private final ProjectKnowledgeRepoRepository knowledgeRepoRepository;

    // -------- reads --------

    @Transactional(readOnly = true)
    public List<SecretResponse> listForProject(UUID projectId) {
        return secretRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(SecretResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SecretResponse> listGlobal() {
        return secretRepository.findAllGlobal().stream()
                .map(SecretResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SecretResponse findById(UUID id) {
        return secretRepository.findById(id)
                .map(SecretResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Secret", id.toString()));
    }

    // -------- writes --------

    @Transactional
    public SecretResponse createForProject(UUID projectId, CreateSecretRequest request, UUID currentUserId) {
        validatePayloadMatchesType(request);

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId.toString()));

        if (secretRepository.existsByProjectIdAndName(projectId, request.name())) {
            throw new DuplicateResourceException("Secret", "name", request.name());
        }

        Secret secret = new Secret();
        secret.setProject(project);
        return persist(secret, request, currentUserId);
    }

    @Transactional
    public SecretResponse createGlobal(CreateSecretRequest request, UUID currentUserId) {
        validatePayloadMatchesType(request);

        if (secretRepository.existsGlobalByName(request.name())) {
            throw new DuplicateResourceException("Secret", "name", request.name());
        }

        Secret secret = new Secret();
        secret.setProject(null);
        return persist(secret, request, currentUserId);
    }

    @Transactional
    public SecretResponse update(UUID id, UpdateSecretRequest request) {
        Secret secret = secretRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Secret", id.toString()));

        if (request.payload() != null) {
            if (request.payload().type() != secret.getType()) {
                throw new SecretTypeMismatchException(
                        "Cannot change secret type from " + secret.getType()
                                + " to " + request.payload().type());
            }
            backendRegistry.forSecret(secret).write(secret, request.payload());
        }

        secret = secretRepository.save(secret);
        log.info("Updated secret {} ({})", secret.getName(), id);
        return SecretResponse.from(secret);
    }

    @Transactional
    public void delete(UUID id) {
        Secret secret = secretRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Secret", id.toString()));

        long refs = knowledgeRepoRepository.countByCredentialSecretId(id);
        if (refs > 0) {
            throw ResourceInUseException.blockedBy("KnowledgeRepo", (int) refs);
        }

        try {
            backendRegistry.forSecret(secret).delete(secret);
        } catch (RuntimeException e) {
            log.warn("Backend delete failed for secret {}: {}", id, e.getMessage());
        }
        secretRepository.delete(secret);
        log.info("Deleted secret {} ({})", secret.getName(), id);
    }

    // -------- helpers --------

    private SecretResponse persist(Secret secret, CreateSecretRequest request, UUID currentUserId) {
        secret.setName(request.name());
        secret.setType(request.type());
        secret.setBackend(request.backend() != null ? request.backend() : SecretBackend.LOCAL);

        if (currentUserId != null) {
            User user = userRepository.findById(currentUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", currentUserId.toString()));
            secret.setCreatedBy(user);
        }

        // For LOCAL backend (the only one wired today) encrypt the payload now.
        // Future external backends would push to remote and populate backendRef.
        if (secret.getBackend() == SecretBackend.LOCAL) {
            localBackend.write(secret, request.payload());
        } else {
            throw new UnsupportedOperationException(
                    "Backend " + secret.getBackend() + " is not yet supported");
        }

        secret = secretRepository.save(secret);
        log.info("Created secret {} (type={}, scope={})",
                secret.getName(), secret.getType(), secret.isGlobal() ? "GLOBAL" : "PROJECT");
        return SecretResponse.from(secret);
    }

    private void validatePayloadMatchesType(CreateSecretRequest request) {
        if (request.payload() == null) {
            throw new IllegalArgumentException("Payload is required");
        }
        if (request.payload().type() != request.type()) {
            throw new SecretTypeMismatchException(
                    "Payload type " + request.payload().type()
                            + " does not match declared type " + request.type());
        }
    }
}
