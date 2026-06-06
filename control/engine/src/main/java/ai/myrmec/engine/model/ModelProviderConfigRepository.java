package ai.myrmec.engine.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for ModelProviderConfig entities.
 */
@Repository
public interface ModelProviderConfigRepository extends JpaRepository<ModelProviderConfig, String> {

    /**
     * Find all providers with the given status.
     */
    List<ModelProviderConfig> findByStatus(ModelStatus status);

    /**
     * Find all active providers.
     */
    default List<ModelProviderConfig> findAllActive() {
        return findByStatus(ModelStatus.ACTIVE);
    }

    /**
     * Find providers by deployment type.
     */
    List<ModelProviderConfig> findByDeploymentType(DeploymentType deploymentType);

    /**
     * Find all cloud providers.
     */
    default List<ModelProviderConfig> findAllCloud() {
        return findByDeploymentType(DeploymentType.CLOUD);
    }

    /**
     * Find all on-premise providers.
     */
    default List<ModelProviderConfig> findAllOnPremise() {
        return findByDeploymentType(DeploymentType.ON_PREMISE);
    }

    /**
     * Check if a provider exists by code.
     */
    boolean existsByCode(String code);
}
