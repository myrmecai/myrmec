package ai.myrmec.engine.workflow;

import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.workflow.dto.AccessibleProjectResponse;
import ai.myrmec.engine.workflow.dto.PagedResponse;
import ai.myrmec.engine.workflow.dto.WorkflowListItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-project workflow list endpoints. Scoped to the calling user's
 * accessible projects (system-wide role holders see everything).
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowsListController {

    private static final String NEVER_TOKEN = "NEVER";

    private final WorkflowListService workflowListService;

    @GetMapping
    public PagedResponse<WorkflowListItemResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(name = "status", required = false) List<WorkflowStatus> statuses,
            @RequestParam(name = "projectId", required = false) List<UUID> projectIds,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "lastRunStatus", required = false) List<String> lastRunStatusTokens,
            @RequestParam(name = "createdFrom", required = false) Instant createdFrom,
            @RequestParam(name = "createdTo", required = false) Instant createdTo,
            @RequestParam(name = "lastRunFrom", required = false) Instant lastRunFrom,
            @RequestParam(name = "lastRunTo", required = false) Instant lastRunTo,
            @RequestParam(name = "sort", defaultValue = "createdAt") String sort,
            @RequestParam(name = "direction", defaultValue = "desc") String direction,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size
    ) {
        Set<RequestStatus> runStatuses = EnumSet.noneOf(RequestStatus.class);
        boolean includeNeverRun = false;
        if (lastRunStatusTokens != null) {
            for (String token : lastRunStatusTokens) {
                if (token == null) {
                    continue;
                }
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (NEVER_TOKEN.equalsIgnoreCase(trimmed)) {
                    includeNeverRun = true;
                } else {
                    runStatuses.add(RequestStatus.valueOf(trimmed.toUpperCase()));
                }
            }
        }

        WorkflowListService.Filter filter = new WorkflowListService.Filter(
                statuses == null ? null : new HashSet<>(statuses),
                projectIds == null ? null : new HashSet<>(projectIds),
                search,
                runStatuses.isEmpty() ? null : runStatuses,
                includeNeverRun,
                createdFrom,
                createdTo,
                lastRunFrom,
                lastRunTo
        );
        WorkflowListService.Pageable pageable = new WorkflowListService.Pageable(page, size, sort, direction);
        return workflowListService.list(principal.getUserId(), filter, pageable);
    }

    @GetMapping("/accessible-projects")
    public List<AccessibleProjectResponse> accessibleProjects(
            @AuthenticationPrincipal UserPrincipal principal) {
        return workflowListService.accessibleProjects(principal.getUserId());
    }
}
