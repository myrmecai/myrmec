package ai.myrmec.engine._controllers.team;

import ai.myrmec.engine.team.TeamMemberService;
import ai.myrmec.engine.team.dto.AssignTeamMemberRequest;
import ai.myrmec.engine.team.dto.TeamMemberCandidate;
import ai.myrmec.engine.team.dto.TeamMemberListResponse;
import ai.myrmec.engine.team.dto.TeamMemberResponse;
import ai.myrmec.engine.user.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Manage Team-scoped user memberships — viewers/editors of the Team.
 *
 * <p>Member management is owner-level (see {@code TeamAccessEvaluator#canOwn}):
 * Team-scoped {@code TEAM_OWNER} or system-wide {@code ORG_ADMIN} (governance
 * bypass). A Team EDITOR cannot add/remove members — granting roles, including
 * {@code TEAM_OWNER}, is a governance action reserved to owners.
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/members")
@RequiredArgsConstructor
public class TeamMemberController {

    private final TeamMemberService teamMemberService;

    @GetMapping
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public TeamMemberListResponse list(@PathVariable UUID teamId) {
        return teamMemberService.list(teamId);
    }

    @GetMapping("/candidates")
    @PreAuthorize("@teamAccess.canOwn(#teamId, authentication)")
    public List<TeamMemberCandidate> listCandidates(@PathVariable UUID teamId) {
        return teamMemberService.listCandidates(teamId);
    }

    @PostMapping
    @PreAuthorize("@teamAccess.canOwn(#teamId, authentication)")
    public ResponseEntity<TeamMemberResponse> assign(
            @PathVariable UUID teamId,
            @Valid @RequestBody AssignTeamMemberRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        TeamMemberResponse response = teamMemberService.assign(teamId, request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{userId}/roles/{roleId}")
    @PreAuthorize("@teamAccess.canOwn(#teamId, authentication)")
    public ResponseEntity<Void> remove(
            @PathVariable UUID teamId,
            @PathVariable UUID userId,
            @PathVariable UUID roleId) {
        teamMemberService.remove(teamId, userId, roleId);
        return ResponseEntity.noContent().build();
    }
}
