package ai.myrmec.engine.harness;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HarnessAgentBindingRepository extends JpaRepository<HarnessAgentBinding, UUID> {

    List<HarnessAgentBinding> findByHarnessIdOrderByRoleTypeAsc(UUID harnessId);

    List<HarnessAgentBinding> findByHarnessIdAndRoleType(UUID harnessId, AgentRoleType roleType);

    boolean existsByHarnessIdAndAgentAlias(UUID harnessId, String agentAlias);
}
