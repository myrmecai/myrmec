package ai.myrmec.engine.serviceaccount;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ServiceAccount}. {@link #findByKeycloakClientId} is the
 * hot path: the External API filter chain resolves a validated client-credentials
 * token to its owning account by the token's client/azp claim.
 */
public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, UUID> {

    /** Resolve a validated external token (by its Keycloak client id) to its account. */
    Optional<ServiceAccount> findByKeycloakClientId(String keycloakClientId);

    boolean existsByKeycloakClientId(String keycloakClientId);

    List<ServiceAccount> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<ServiceAccount> findAllByOrderByCreatedAtDesc();
}
