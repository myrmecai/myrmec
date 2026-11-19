package ai.myrmec.engine.workflow.dto;

import java.util.UUID;

/**
 * Minimal Team descriptor used to populate the Team filter on the
 * cross-Team workflows view. Returns only projects the current user can
 * access.
 */
public record AccessibleTeamResponse(
        UUID id,
        String name
) {}
