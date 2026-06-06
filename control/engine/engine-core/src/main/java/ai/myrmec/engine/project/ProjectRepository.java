package ai.myrmec.engine.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for Project entities.
 */
@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /**
     * Find all projects by status.
     */
    List<Project> findByStatus(ProjectStatus status);

    /**
     * Check if a project with the given name exists.
     */
    boolean existsByName(String name);

    /**
     * Find all projects belonging to the given group.
     */
    List<Project> findByGroupId(UUID groupId);

    /**
     * Count projects in a given group (used to block group deletion).
     */
    long countByGroupId(UUID groupId);
}
