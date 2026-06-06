package ai.myrmec.engine.group;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.group.dto.AssignGroupMemberRequest;
import ai.myrmec.engine.group.dto.GroupMemberResponse;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserRoleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manage group-scoped role assignments. {@code ORG_ADMIN} only.
 *
 * <p>Roles meaningful at GROUP scope:
 * {@code EDITOR}, {@code VIEWER}, {@code BUDGET_OWNER}, {@code APPROVER},
 * {@code AUDITOR}. {@code PLATFORM_ADMIN}, {@code ORG_ADMIN} and
 * {@code PROJECT_OWNER} are rejected at group scope.
 */
@RestController
@RequestMapping("/api/v1/admin/groups/{groupId}/members")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ORG_ADMIN')")
@Tag(name = "Groups", description = "Group governance operations")
public class GroupMemberController {

    private static final Set<UserRole.Role> ALLOWED_GROUP_ROLES = EnumSet.of(
            UserRole.Role.EDITOR,
            UserRole.Role.VIEWER,
            UserRole.Role.BUDGET_OWNER,
            UserRole.Role.APPROVER,
            UserRole.Role.AUDITOR);

    private final GroupRepository groupRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;

    @GetMapping
    @Operation(summary = "List members assigned at this group")
    @Transactional(readOnly = true)
    public ResponseEntity<List<GroupMemberResponse>> list(@PathVariable UUID groupId) {
        ensureGroup(groupId);

        List<UserRole> rows = userRoleRepository.findByGroupId(groupId);
        Set<UUID> userIds = rows.stream().map(UserRole::getUserId).collect(Collectors.toSet());
        Map<UUID, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<GroupMemberResponse> members = rows.stream()
                .map(r -> {
                    User u = usersById.get(r.getUserId());
                    if (u == null) return null;
                    return GroupMemberResponse.builder()
                            .roleId(r.getId())
                            .userId(u.getId())
                            .email(u.getEmail())
                            .name(u.getName())
                            .role(r.getRole().name())
                            .build();
                })
                .filter(m -> m != null)
                .sorted(Comparator
                        .comparing(GroupMemberResponse::getEmail,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(GroupMemberResponse::getRole))
                .toList();

        return ResponseEntity.ok(members);
    }

    @PostMapping
    @Operation(summary = "Assign a group-scoped role to a user")
    @Transactional
    public ResponseEntity<GroupMemberResponse> assign(
            @PathVariable UUID groupId,
            @Valid @RequestBody AssignGroupMemberRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        ensureGroup(groupId);

        if (!ALLOWED_GROUP_ROLES.contains(request.getRole())) {
            throw new BadRequestException(request.getRole() + " is not assignable at group scope");
        }
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> ResourceNotFoundException.of("User", "id", request.getUserId().toString()));
        if (Boolean.FALSE.equals(user.getIsActive())) {
            throw new BadRequestException("User is inactive and cannot be assigned to a group");
        }

        // Idempotent: if (user, role, group) already exists, return it.
        UserRole existing = userRoleRepository.findByUserIdAndGroupId(user.getId(), groupId).stream()
                .filter(r -> r.getRole() == request.getRole())
                .findFirst()
                .orElse(null);

        UserRole row = existing != null ? existing : new UserRole();
        if (existing == null) {
            row.setUserId(user.getId());
            row.setRole(request.getRole());
            row.setScopeType(UserRole.ScopeType.GROUP);
            row.setGroupId(groupId);
            row.setGrantedByUserId(principal != null ? principal.getUserId() : null);
            row = userRoleRepository.save(row);
            log.info("Assigned user {} as {} on group {} (granted by {})",
                    user.getId(), request.getRole(), groupId,
                    principal != null ? principal.getUserId() : null);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(GroupMemberResponse.builder()
                .roleId(row.getId())
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(row.getRole().name())
                .build());
    }

    @DeleteMapping("/{roleId}")
    @Operation(summary = "Remove a group-scoped role assignment")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable UUID groupId, @PathVariable UUID roleId) {
        ensureGroup(groupId);

        UserRole row = userRoleRepository.findById(roleId)
                .orElseThrow(() -> ResourceNotFoundException.of("GroupMemberRole", "id", roleId.toString()));
        if (!groupId.equals(row.getGroupId())) {
            throw ResourceNotFoundException.of("GroupMemberRole", "id", roleId.toString());
        }
        userRoleRepository.delete(row);
        log.info("Removed group role {} from group {}", roleId, groupId);
        return ResponseEntity.noContent().build();
    }

    private void ensureGroup(UUID groupId) {
        if (!groupRepository.existsById(groupId)) {
            throw ResourceNotFoundException.of("Group", "id", groupId.toString());
        }
    }
}
