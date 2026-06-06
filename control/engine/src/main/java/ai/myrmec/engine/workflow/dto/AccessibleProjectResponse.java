package ai.myrmec.engine.workflow.dto;

import java.util.UUID;

/**
 * Minimal project descriptor used to populate the project filter on the
 * cross-project workflows view. Returns only projects the current user can
 * access.
 */
public record AccessibleProjectResponse(
        UUID id,
        String name
) {}
