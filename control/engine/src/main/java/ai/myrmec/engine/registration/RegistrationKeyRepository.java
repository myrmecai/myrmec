package ai.myrmec.engine.registration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RegistrationKeyRepository extends JpaRepository<RegistrationKey, UUID> {

    Optional<RegistrationKey> findByKeyHash(String keyHash);
}
