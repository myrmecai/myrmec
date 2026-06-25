package ai.myrmec.engine.serviceaccount;

import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.serviceaccount.dto.CreateServiceAccountRequest;
import ai.myrmec.engine.serviceaccount.dto.UpdateServiceAccountRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * Provisioning + lookup for {@link ServiceAccount}s (External API, #95).
 *
 * <p>Admin provisioning ({@link #create}/{@link #update}/{@link #list}/{@link #get})
 * is exercised by the admin REST surface. {@link #resolveActiveByClientId} is the
 * authentication hot path used by the External API filter chain to turn a
 * validated Keycloak {@code client_id} into an enabled account.</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceAccountService {

    private final ServiceAccountRepository serviceAccountRepository;
    private final ProjectRepository projectRepository;

    @Transactional
    public ServiceAccount create(CreateServiceAccountRequest request, UUID createdBy) {
        if (!projectRepository.existsById(request.projectId())) {
            throw ResourceNotFoundException.project(request.projectId());
        }
        if (serviceAccountRepository.existsByKeycloakClientId(request.keycloakClientId())) {
            throw new DuplicateResourceException(
                    "ServiceAccount", "keycloakClientId", request.keycloakClientId());
        }

        ServiceAccount sa = new ServiceAccount();
        sa.setProjectId(request.projectId());
        sa.setName(request.name());
        sa.setDescription(request.description());
        sa.setKeycloakClientId(request.keycloakClientId());
        sa.setEnabled(true);
        if (request.rateLimitPerMin() != null) {
            sa.setRateLimitPerMin(request.rateLimitPerMin());
        }
        sa.setCreatedBy(createdBy);
        return serviceAccountRepository.save(sa);
    }

    @Transactional(readOnly = true)
    public List<ServiceAccount> list(UUID projectId) {
        return projectId == null
                ? serviceAccountRepository.findAllByOrderByCreatedAtDesc()
                : serviceAccountRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public ServiceAccount get(UUID id) {
        return serviceAccountRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("ServiceAccount", id));
    }

    @Transactional
    public ServiceAccount update(UUID id, UpdateServiceAccountRequest request) {
        ServiceAccount sa = get(id);
        if (StringUtils.hasText(request.name())) {
            sa.setName(request.name());
        }
        if (request.description() != null) {
            sa.setDescription(request.description());
        }
        if (request.enabled() != null) {
            sa.setEnabled(request.enabled());
        }
        if (request.rateLimitPerMin() != null) {
            sa.setRateLimitPerMin(request.rateLimitPerMin());
        }
        return serviceAccountRepository.save(sa);
    }

    /**
     * Authentication hot path: resolve a validated external token's Keycloak
     * {@code client_id} to an <em>enabled</em> account. Disabled or unknown
     * client ids yield empty (caller maps to 403).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ServiceAccount> resolveActiveByClientId(String keycloakClientId) {
        return serviceAccountRepository.findByKeycloakClientId(keycloakClientId)
                .filter(ServiceAccount::isEnabled);
    }
}
