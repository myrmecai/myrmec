package ai.myrmec.engine.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Model entities.
 */
@Repository
public interface ModelRepository extends JpaRepository<Model, String> {

    /**
     * Check if a model with the given code exists.
     */
    boolean existsByCode(String code);

    /**
     * Find all models with provider config eagerly loaded.
     */
    @Query("SELECT m FROM Model m LEFT JOIN FETCH m.providerConfig")
    List<Model> findAllWithProvider();

    /**
     * Find model by code with provider config eagerly loaded.
     */
    @Query("SELECT m FROM Model m LEFT JOIN FETCH m.providerConfig WHERE m.code = :code")
    Optional<Model> findByCodeWithProvider(String code);

    /**
     * Find all models with a specific status.
     */
    @Query("SELECT m FROM Model m LEFT JOIN FETCH m.providerConfig WHERE m.status = :status")
    List<Model> findByStatus(ModelStatus status);

    /**
     * Find all models with a specific deployment type.
     */
    List<Model> findByDeploymentType(DeploymentType deploymentType);

    /**
     * Find all models with a specific provider.
     */
    List<Model> findByProvider(String provider);

    /**
     * Find all active models.
     */
    default List<Model> findAllActive() {
        return findByStatus(ModelStatus.ACTIVE);
    }

    /**
     * Find all on-premise models (for health monitoring).
     */
    default List<Model> findAllOnPremise() {
        return findByDeploymentType(DeploymentType.ON_PREMISE);
    }
}
