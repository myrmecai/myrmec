package ai.myrmec.engine.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuthenticationProviderRepository extends JpaRepository<AuthenticationProvider, String> {

    List<AuthenticationProvider> findByIsEnabledTrueOrderByCodeAsc();

    long countByIsEnabledTrueAndProviderTypeNot(AuthenticationProvider.ProviderType providerType);
}
