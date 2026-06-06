package ai.myrmec.engine.group;

import ai.myrmec.engine.group.dto.CreateGroupRequest;
import ai.myrmec.engine.group.dto.GroupResponse;
import ai.myrmec.engine.group.dto.UpdateGroupRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin REST controller for groups. {@code ORG_ADMIN} only — group CRUD is a
 * governance operation. Read access is shared with {@code PLATFORM_ADMIN} so
 * platform tooling (e.g. project listings) can resolve group names.
 */
@RestController
@RequestMapping("/api/v1/admin/groups")
@RequiredArgsConstructor
@Tag(name = "Groups", description = "Group governance operations")
public class GroupController {

    private final GroupService groupService;

    @GetMapping
    @Operation(summary = "List all groups")
    public ResponseEntity<List<GroupResponse>> listGroups() {
        List<GroupResponse> response = groupService.findAll().stream()
                .map(GroupResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a group by id")
    public ResponseEntity<GroupResponse> getGroup(@PathVariable UUID id) {
        return ResponseEntity.ok(GroupResponse.from(groupService.findById(id)));
    }

    @PostMapping
    @Operation(summary = "Create a new group")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public ResponseEntity<GroupResponse> createGroup(@Valid @RequestBody CreateGroupRequest request) {
        Group group = groupService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(GroupResponse.from(group));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update group metadata")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public ResponseEntity<GroupResponse> updateGroup(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateGroupRequest request) {
        Group group = groupService.update(id, request);
        return ResponseEntity.ok(GroupResponse.from(group));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a group (must be empty of projects)")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        groupService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
