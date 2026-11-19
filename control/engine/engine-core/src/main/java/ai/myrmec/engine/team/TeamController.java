package ai.myrmec.engine.team;

import ai.myrmec.engine.team.dto.CreateTeamRequest;
import ai.myrmec.engine.team.dto.MoveTeamGroupRequest;
import ai.myrmec.engine.team.dto.TeamResponse;
import ai.myrmec.engine.team.dto.UpdateTeamRequest;
import ai.myrmec.engine.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for Team management.
 */
@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
@Tag(name = "teams", description = "Team management operations")
public class TeamController {

    private final TeamService teamService;

    @GetMapping
    @Operation(summary = "List teams visible to the caller")
    public ResponseEntity<List<TeamResponse>> listTeams(Authentication authentication) {
        // RBAC-05: scope-filter — return only teams the caller can view,
        // not every Team in the org.
        UserPrincipal user = authentication != null && authentication.getPrincipal() instanceof UserPrincipal up
                ? up : null;
        List<Team> teams = teamService.findAllVisibleTo(user);
        List<TeamResponse> response = teams.stream()
                .map(TeamResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get Team by ID")
    @PreAuthorize("@teamAccess.canView(#id, authentication)")
    public ResponseEntity<TeamResponse> getTeam(@PathVariable UUID id) {
        Team team = teamService.findById(id);
        return ResponseEntity.ok(TeamResponse.from(team));
    }

    @PostMapping
    @Operation(summary = "Create a new Team")
    @PreAuthorize("hasRole('ORG_ADMIN') or (#request.groupId != null and @groupAccess.canEdit(#request.groupId, authentication))")
    public ResponseEntity<TeamResponse> createTeam(
            @Valid @RequestBody CreateTeamRequest request) {

        Team team = teamService.create(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TeamResponse.from(team));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update Team details")
    @PreAuthorize("@teamAccess.canEdit(#id, authentication)")
    public ResponseEntity<TeamResponse> updateTeam(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTeamRequest request) {

        Team team = teamService.update(id, request);

        return ResponseEntity.ok(TeamResponse.from(team));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a Team")
    @PreAuthorize("@teamAccess.canOwn(#id, authentication)")
    public ResponseEntity<Void> deleteTeam(@PathVariable UUID id) {
        teamService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/move-group")
    @Operation(summary = "Move a Team to a different group")
    @PreAuthorize("@teamAccess.canOwn(#id, authentication) and @groupAccess.canEdit(#request.groupId, authentication)")
    public ResponseEntity<TeamResponse> moveTeamToGroup(
            @PathVariable UUID id,
            @Valid @RequestBody MoveTeamGroupRequest request) {

        Team team = teamService.moveToGroup(id, request.getGroupId());
        return ResponseEntity.ok(TeamResponse.from(team));
    }
}
