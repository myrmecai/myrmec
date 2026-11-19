package ai.myrmec.engine.team.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Combined response for the Team Members page.
 */
@Data
@Builder
public class TeamMemberListResponse {

    /** Users with an explicit role on this Team. */
    private List<TeamMemberResponse> teamMembers;

    /** Users with system-wide roles (implicit access). Read-only on this page. */
    private List<SystemWideUserSummary> systemWideUsers;
}
