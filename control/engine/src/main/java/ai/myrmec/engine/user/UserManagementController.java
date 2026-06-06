package ai.myrmec.engine.user;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine.user.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for user management (admin only).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "Admin operations for managing users")
public class UserManagementController {

    private final UserService userService;
    private final UserRoleRepository userRoleRepository;

    @GetMapping
    @Operation(summary = "List all users")
    public ResponseEntity<List<UserResponse>> listUsers() {
        List<User> users = userService.findAll();
        List<UserResponse> response = users.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get user by ID")
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID userId) {
        User user = userService.findById(userId);
        return ResponseEntity.ok(toResponse(user));
    }

    @PostMapping
    @Operation(summary = "Create a new user")
    public ResponseEntity<UserResponse> createUser(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        String providerCode = request.getProviderCode().trim().toUpperCase();

        User user;
        if (AuthenticationProvider.LOCAL_CODE.equals(providerCode)) {
            if (request.getPassword() == null || request.getPassword().isBlank()) {
                throw new BadRequestException("Password is required for LOCAL users");
            }
            user = userService.createLocalUser(
                    request.getEmail(),
                    request.getName(),
                    request.getPassword(),
                    principal != null ? principal.getUserId() : null
            );
        } else {
            user = userService.createExternalUser(
                    request.getEmail(),
                    request.getName(),
                    providerCode,
                    principal != null ? principal.getUserId() : null
            );
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(user));
    }

    @PatchMapping("/{userId}")
    @Operation(summary = "Update user details")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRequest request) {

        User user = userService.findById(userId);

        // System users cannot be edited
        if (Boolean.TRUE.equals(user.getIsSystem())) {
            throw new BadRequestException("Cannot modify system user");
        }

        if (request.getName() != null) {
            user.setName(request.getName());
        }

        if (request.getProviderCode() != null) {
            String providerCode = request.getProviderCode().trim().toUpperCase();
            if (Boolean.TRUE.equals(user.getIsSystem()) && !AuthenticationProvider.LOCAL_CODE.equals(providerCode)) {
                throw new BadRequestException("Bootstrap admin provider is immutable; only password updates are allowed.");
            }
            user.setProviderCode(providerCode);
        }

        if (request.getIsActive() != null) {
            user.setIsActive(request.getIsActive());
        }

        user = userService.save(user);
        return ResponseEntity.ok(toResponse(user));
    }

    @PostMapping("/{userId}/roles")
    @Operation(summary = "Assign role to user")
    public ResponseEntity<UserResponse> assignRole(
            @PathVariable UUID userId,
            @Valid @RequestBody AssignRoleRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        User user = userService.findById(userId);
        
        // System users' roles cannot be modified
        if (Boolean.TRUE.equals(user.getIsSystem())) {
            throw new BadRequestException("Cannot modify system user roles");
        }

        userService.assignRole(
                userId,
                request.getRole(),
                request.getScopeType(),
                request.getGroupId(),
                request.getProjectId(),
                principal != null ? principal.getUserId() : null
        );

        user = userService.findById(userId);
        return ResponseEntity.ok(toResponse(user));
    }

    @DeleteMapping("/{userId}/roles/{roleId}")
    @Operation(summary = "Remove role from user")
    public ResponseEntity<UserResponse> removeRole(
            @PathVariable UUID userId,
            @PathVariable UUID roleId) {

        User user = userService.findById(userId);
        
        // System users' roles cannot be modified
        if (Boolean.TRUE.equals(user.getIsSystem())) {
            throw new BadRequestException("Cannot modify system user roles");
        }

        userRoleRepository.deleteById(roleId);

        user = userService.findById(userId);
        return ResponseEntity.ok(toResponse(user));
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "Deactivate user")
    public ResponseEntity<Void> deactivateUser(@PathVariable UUID userId) {
        userService.deactivateUser(userId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{userId}/password")
    @Operation(summary = "Update user password")
    public ResponseEntity<Void> updatePassword(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdatePasswordRequest request) {
        userService.updatePassword(userId, request.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    private UserResponse toResponse(User user) {
        List<UserRole> roles = userRoleRepository.findByUserId(user.getId());
        List<UserResponse.RoleInfo> roleInfos = roles.stream()
                .map(r -> UserResponse.RoleInfo.builder()
                        .id(r.getId())
                        .role(r.getRole().name())
                        .projectId(r.getProjectId())
                        .createdAt(r.getCreatedAt())
                        .build())
                .toList();

        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
            .providerCode(user.getProviderCode())
                .isActive(user.getIsActive())
                .isSystem(user.getIsSystem())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .roles(roleInfos)
                .build();
    }
}
