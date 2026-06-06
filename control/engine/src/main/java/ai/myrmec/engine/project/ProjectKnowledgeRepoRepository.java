package ai.myrmec.engine.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectKnowledgeRepoRepository extends JpaRepository<ProjectKnowledgeRepo, UUID> {

    List<ProjectKnowledgeRepo> findByProjectIdOrderByNameAsc(UUID projectId);

    Optional<ProjectKnowledgeRepo> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    boolean existsByProjectIdAndNameAndIdNot(UUID projectId, String name, UUID id);

    long countByCredentialSecretId(UUID credentialSecretId);
}
