package ai.myrmec.engine.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentProfileRepository extends JpaRepository<AgentProfile, UUID> {

    Optional<AgentProfile> findByName(String name);

    boolean existsByName(String name);

    List<AgentProfile> findByStatus(AgentProfile.Status status);

    @Query("SELECT DISTINCT p FROM AgentProfile p LEFT JOIN FETCH p.tools ORDER BY p.name")
    List<AgentProfile> findAllWithTools();

    @Query("SELECT DISTINCT p FROM AgentProfile p LEFT JOIN FETCH p.tools WHERE p.status = 'ACTIVE' ORDER BY p.name")
    List<AgentProfile> findAllActiveWithTools();

    @Query("SELECT p FROM AgentProfile p LEFT JOIN FETCH p.tools WHERE p.id = :id")
    Optional<AgentProfile> findByIdWithTools(UUID id);

    @Query("SELECT p FROM AgentProfile p WHERE p.status = 'ACTIVE' ORDER BY p.name")
    List<AgentProfile> findAllActive();

    @Query("SELECT COUNT(a) FROM Agent a WHERE a.profileId = :profileId")
    long countAgentsByProfileId(UUID profileId);
}
