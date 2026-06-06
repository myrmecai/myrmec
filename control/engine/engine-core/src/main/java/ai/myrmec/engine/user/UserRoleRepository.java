package ai.myrmec.engine.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    List<UserRole> findByUserId(UUID userId);

    List<UserRole> findByUserIdAndProjectId(UUID userId, UUID projectId);

    List<UserRole> findByUserIdAndProjectIdIsNull(UUID userId);

    List<UserRole> findByProjectId(UUID projectId);

    List<UserRole> findByProjectIdIsNull();

    List<UserRole> findByRoleAndProjectIdIsNull(UserRole.Role role);

    boolean existsByUserIdAndRoleAndProjectIdIsNull(UUID userId, UserRole.Role role);

    List<UserRole> findByGroupId(UUID groupId);

    List<UserRole> findByUserIdAndGroupId(UUID userId, UUID groupId);

    void deleteByUserId(UUID userId);
}
