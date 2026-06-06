package ai.myrmec.engine.project.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Combined response for the project Members page.
 */
@Data
@Builder
public class ProjectMemberListResponse {

    /** Users with an explicit role on this project. */
    private List<ProjectMemberResponse> projectMembers;

    /** Users with system-wide roles (implicit access). Read-only on this page. */
    private List<SystemWideUserSummary> systemWideUsers;
}
