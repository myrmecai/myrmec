package ai.myrmec.engine.user;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing users and their roles.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    // BCrypt with 10 rounds (~100ms hash time, good balance of security/performance)
    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(10);

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordValidator passwordValidator;
    private final AuthProviderService authProviderService;

    /**
     * Find all users.
     */
    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAll();
    }

    /**
     * Find user by ID.
     */
    @Transactional(readOnly = true)
    public User findById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
    }

    /**
     * Find user by email.
     */
    @Transactional(readOnly = true)
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> ResourceNotFoundException.of("User", "email", email));
    }

    /**
     * Save user.
     */
    @Transactional
    public User save(User user) {
        return userRepository.save(user);
    }

    /**
     * Get all roles for a user.
     */
    @Transactional(readOnly = true)
    public List<UserRole> getRoles(UUID userId) {
        return userRoleRepository.findByUserId(userId);
    }

    /**
     * Create a new LOCAL user with password.
     */
    @Transactional
    public User createLocalUser(String email, String name, String password, UUID createdByUserId) {
        validateEmailNotExists(email);
        passwordValidator.validate(password);
        authProviderService.validateProviderEnabledForUser(AuthenticationProvider.LOCAL_CODE);

        User user = new User();
        user.setEmail(email.toLowerCase().trim());
        user.setName(name);
        user.setPasswordHash(PASSWORD_ENCODER.encode(password));
        user.setProviderCode(AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);

        user = userRepository.save(user);
        log.info("Created LOCAL user: {} (created by: {})", email, createdByUserId);
        return user;
    }

    /**
     * Create a new external user (no password).
     */
    @Transactional
    public User createExternalUser(String email, String name, String providerCode, UUID createdByUserId) {
        validateEmailNotExists(email);
        authProviderService.validateProviderEnabledForUser(providerCode);

        User user = new User();
        user.setEmail(email.toLowerCase().trim());
        user.setName(name);
        user.setPasswordHash(null);
        user.setProviderCode(providerCode.trim().toUpperCase());
        user.setIsActive(true);

        user = userRepository.save(user);
        log.info("Created external user: {} (provider: {}, created by: {})", email, user.getProviderCode(), createdByUserId);
        return user;
    }

    /**
     * Assign a role to a user at the given scope. Idempotent: re-asserting an
     * already-granted (user, role, scope) triple returns the existing row.
     */
    @Transactional
    public UserRole assignRole(UUID userId,
                               UserRole.Role role,
                               UserRole.ScopeType scopeType,
                               UUID groupId,
                               UUID projectId,
                               UUID grantedByUserId) {
        findById(userId);

        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRole(role);
        userRole.setScopeType(scopeType);
        userRole.setGroupId(groupId);
        userRole.setProjectId(projectId);
        userRole.setGrantedByUserId(grantedByUserId);

        userRole = userRoleRepository.save(userRole);

        log.info("Assigned role {} ({}) to user {} (group={}, project={}, granted by={})",
                role, scopeType, userId, groupId, projectId, grantedByUserId);

        return userRole;
    }

    /**
     * Convenience: assign a SYSTEM-scoped role.
     */
    @Transactional
    public UserRole assignSystemRole(UUID userId, UserRole.Role role, UUID grantedByUserId) {
        return assignRole(userId, role, UserRole.ScopeType.SYSTEM, null, null, grantedByUserId);
    }

    /**
        * Verify password for LOCAL authentication.
     * 
     * @return true if password matches
     */
    public boolean verifyPassword(User user, String password) {
        if (!user.canAuthenticateWithPassword()) {
            return false;
        }
        return PASSWORD_ENCODER.matches(password, user.getPasswordHash());
    }

    /**
     * Update user password.
     */
    @Transactional
    public void updatePassword(UUID userId, String newPassword) {
        passwordValidator.validate(newPassword);

        User user = findById(userId);
        if (!AuthenticationProvider.LOCAL_CODE.equals(user.getProviderCode())) {
            throw new BadRequestException("Cannot set password for non-LOCAL user");
        }

        user.setPasswordHash(PASSWORD_ENCODER.encode(newPassword));
        userRepository.save(user);
        log.info("Password updated for user: {}", user.getEmail());
    }

    /**
     * Deactivate a user.
     */
    @Transactional
    public void deactivateUser(UUID userId) {
        User user = findById(userId);
        validateNotSystemUser(user, "deactivate");
        user.setIsActive(false);
        userRepository.save(user);
        log.info("Deactivated user: {}", user.getEmail());
    }

    /**
     * Validate that the user is not a system user.
     * System users cannot be modified or deleted.
     */
    private void validateNotSystemUser(User user, String operation) {
        if (Boolean.TRUE.equals(user.getIsSystem())) {
            throw new BadRequestException("Cannot " + operation + " system user");
        }
    }

    /**
     * Activate a user.
     */
    @Transactional
    public void activateUser(UUID userId) {
        User user = findById(userId);
        user.setIsActive(true);
        userRepository.save(user);
        log.info("Activated user: {}", user.getEmail());
    }

    /**
     * Check if a user has the system-wide PLATFORM_ADMIN role.
     */
    @Transactional(readOnly = true)
    public boolean isPlatformAdmin(UUID userId) {
        return userRoleRepository.existsByUserIdAndRoleAndProjectIdIsNull(userId, UserRole.Role.PLATFORM_ADMIN);
    }

    /**
     * Check if any PLATFORM_ADMIN user exists. Used by bootstrap to decide
     * whether to seed an initial admin.
     */
    @Transactional(readOnly = true)
    public boolean hasAnyPlatformAdmin() {
        return userRoleRepository.findAll().stream()
                .anyMatch(r -> r.getRole() == UserRole.Role.PLATFORM_ADMIN && r.isSystemWide());
    }

    private void validateEmailNotExists(String email) {
        if (userRepository.existsByEmail(email.toLowerCase().trim())) {
            throw BadRequestException.forField("email", "DUPLICATE", "Email already exists");
        }
    }

    /**
     * Backward-compatible alias used by bootstrap until all callers are migrated.
     */
    @Transactional
    public User createInternalUser(String email, String name, String password, UUID createdByUserId) {
        return createLocalUser(email, name, password, createdByUserId);
    }
}
