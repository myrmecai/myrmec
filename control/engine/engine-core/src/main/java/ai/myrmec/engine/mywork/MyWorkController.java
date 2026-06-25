package ai.myrmec.engine.mywork;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.mywork.dto.MyApprovalRow;
import ai.myrmec.engine.mywork.dto.MyArchivedRow;
import ai.myrmec.engine.mywork.dto.MyAssistantRow;
import ai.myrmec.engine.mywork.dto.MyContinueRow;
import ai.myrmec.engine.mywork.dto.MyWorkSummaryResponse;
import ai.myrmec.engine.mywork.dto.MyWorkflowRow;
import ai.myrmec.engine.workflow.WorkflowStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only REST surface behind the unified "My Work" landing page (UC-013).
 *
 * <p>Every endpoint is a personal cross-project roll-up scoped to the caller's
 * VIEW-able projects (resolved inside {@link MyWorkService}), optionally
 * narrowed by the {@code projectIds} chip filter. There are no mutations here:
 * the Approvals and Archived tabs drive their actions through the existing
 * per-resource write endpoints, so authorization lives in exactly one place per
 * mutation. The {@code /api/v1/**} security rule already requires an
 * authenticated principal; an AGENT principal simply resolves to an empty scope.</p>
 */
@RestController
@RequestMapping("/api/v1/my-work")
@RequiredArgsConstructor
@Tag(name = "My Work", description = "Personal cross-project landing surface (UC-013)")
public class MyWorkController {

    private final MyWorkService myWorkService;

    @GetMapping("/summary")
    @Operation(summary = "Counters + first-run hints for every My Work tab")
    public MyWorkSummaryResponse summary(
            Authentication authentication,
            @RequestParam(required = false) List<UUID> projectIds) {
        return myWorkService.summary(authentication, projectIds);
    }

    @GetMapping("/workflows")
    @Operation(summary = "My Work Workflows tab — non-archived workflow definitions with live execution rollup")
    public List<MyWorkflowRow> workflows(
            Authentication authentication,
            @RequestParam(required = false) List<UUID> projectIds,
            @RequestParam(required = false) WorkflowStatus status,
            @RequestParam(required = false) String q) {
        return myWorkService.workflows(authentication, projectIds, status, q);
    }

    @GetMapping("/conversations")
    @Operation(summary = "My Work Conversations tab — active assistants with live session rollup")
    public List<MyAssistantRow> conversations(
            Authentication authentication,
            @RequestParam(required = false) List<UUID> projectIds,
            @RequestParam(required = false) String q) {
        return myWorkService.conversations(authentication, projectIds, q);
    }

    @GetMapping("/conversations/continue")
    @Operation(summary = "My Work Continue rail — the caller's own recently-active sessions")
    public List<MyContinueRow> continueRail(
            Authentication authentication,
            @CurrentUser UUID userId,
            @RequestParam(required = false) List<UUID> projectIds,
            @RequestParam(defaultValue = "5") int limit) {
        return myWorkService.continueRail(authentication, userId, projectIds, limit);
    }

    @GetMapping("/approvals")
    @Operation(summary = "My Work Approvals tab — pending human decisions across the caller's scope")
    public List<MyApprovalRow> approvals(
            Authentication authentication,
            @RequestParam(required = false) List<UUID> projectIds,
            @RequestParam(required = false) String q) {
        return myWorkService.approvals(authentication, projectIds, q);
    }

    @GetMapping("/archived")
    @Operation(summary = "My Work Archived tab — archived service definitions across the caller's scope")
    public List<MyArchivedRow> archived(
            Authentication authentication,
            @RequestParam(required = false) List<UUID> projectIds,
            @RequestParam(required = false) MyArchivedRow.Type type,
            @RequestParam(required = false) String q) {
        return myWorkService.archived(authentication, projectIds, type, q);
    }
}
