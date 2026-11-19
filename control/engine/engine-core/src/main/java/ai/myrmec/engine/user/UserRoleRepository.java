package ai.myrmec.engine.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    List<UserRole> findByUserId(UUID userId);

    List<UserRole> findByUserIdAndTeamId(UUID userId, UUID teamId);

    List<UserRole> findByUserIdAndTeamIdIsNull(UUID userId);

    List<UserRole> findByTeamId(UUID teamId);

    List<UserRole> findByTeamIdIsNull();

    List<UserRole> findByRoleAndTeamIdIsNull(UserRole.Role role);

    boolean existsByUserIdAndRoleAndTeamIdIsNull(UUID userId, UserRole.Role role);

    List<UserRole> findByGroupId(UUID groupId);

    List<UserRole> findByUserIdAndGroupId(UUID userId, UUID groupId);

    void deleteByUserId(UUID userId);
}
