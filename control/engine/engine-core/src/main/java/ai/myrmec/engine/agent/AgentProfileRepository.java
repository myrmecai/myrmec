package ai.myrmec.engine.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentProfileRepository extends JpaRepository<AgentProfile, UUID> {

    boolean existsByName(String name);

    Optional<AgentProfile> findByName(String name);

    List<AgentProfile> findByStatus(AgentProfile.Status status);

    List<AgentProfile> findAllByOrderByName();
}
