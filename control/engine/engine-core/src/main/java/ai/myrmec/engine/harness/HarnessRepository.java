package ai.myrmec.engine.harness;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface HarnessRepository extends JpaRepository<Harness, UUID> {
}
