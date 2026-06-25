package ai.myrmec.engine.mywork.dto;

/**
 * Aggregated counters for the "My Work" landing surface (UC-013), scoped to the
 * caller's accessible projects (optionally narrowed by a {@code projectIds}
 * filter). Backs a single {@code GET /api/v1/my-work/summary} call so the page
 * header can render every tab counter plus the first-run banner and the
 * get-started sparkle cues without four round-trips.
 */
public record MyWorkSummaryResponse(
        TabCounter workflows,
        TabCounter conversations,
        long approvalsPending,
        long archivedCount,
        boolean firstRun,
        boolean canCreateWorkflow,
        boolean canCreateConversation) {

    /**
     * The {@code (active / created)} convention shared by the Workflows and
     * Conversations tabs. {@code active} counts definitions with at least one
     * in-flight execution / session (each definition contributes 0 or 1, never
     * the sum of its runs); {@code created} counts non-archived definitions.
     */
    public record TabCounter(long active, long created) {
    }
}
