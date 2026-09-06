package ai.myrmec.engine.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentProfileRepository extends JpaRepository<AgentProfile, UUID> {

    boolean existsByName(String name);

    List<AgentProfile> findByStatus(AgentProfile.Status status);

    List<AgentProfile> findAllByOrderByName();
}
