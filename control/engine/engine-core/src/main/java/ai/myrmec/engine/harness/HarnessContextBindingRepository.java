package ai.myrmec.engine.harness;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HarnessContextBindingRepository extends JpaRepository<HarnessContextBinding, UUID> {

    List<HarnessContextBinding> findByHarnessId(UUID harnessId);

    List<HarnessContextBinding> findByHarnessIdAndBindingType(UUID harnessId, String bindingType);

    Optional<HarnessContextBinding> findByHarnessIdAndBindingTypeAndTargetId(
            UUID harnessId, String bindingType, UUID targetId);
}
