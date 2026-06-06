package ai.myrmec.engine.tool;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.tool.dto.CreateToolRequest;
import ai.myrmec.engine.tool.dto.ToolResponse;
import ai.myrmec.engine.tool.dto.UpdateToolRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9f &mdash; tool description hash pinning. Verifies:
 *
 * <ol>
 *   <li>A freshly-created tool starts unapproved (drift = true).</li>
 *   <li>An admin approve action pins the SHA-256 of the current
 *       description and flags the tool approved.</li>
 *   <li>Re-saving the same description leaves the approval intact.</li>
 *   <li>Editing the description clears the approval (drift detected).</li>
 *   <li>Re-approving after the edit pins the new hash.</li>
 * </ol>
 */
class ToolDescriptionHashTest extends IntegrationTestBase {

    @Autowired
    private ToolService toolService;

    @Autowired
    private ToolRepository toolRepository;

    private static final String TOOL_CODE = "phase9ftesttool";

    @AfterEach
    void cleanup() {
        toolRepository.findById(TOOL_CODE).ifPresent(toolRepository::delete);
    }

    @Test
    void freshToolStartsUnapproved_thenApproveAndDriftCycle() {
        UUID adminId = UUID.randomUUID();

        // 1. Create.
        ToolResponse created = toolService.create(new CreateToolRequest(
                TOOL_CODE,
                "Test Tool",
                "Original description, harmless.",
                ToolType.CUSTOM,
                java.util.Map.of(),
                null,
                ai.myrmec.engine.tool.RiskClass.SAFE));
        assertThat(created.code()).isEqualTo(TOOL_CODE);
        assertThat(toolService.isDescriptionApproved(TOOL_CODE)).isFalse();

        // 2. Approve.
        toolService.approveDescription(TOOL_CODE, adminId);
        assertThat(toolService.isDescriptionApproved(TOOL_CODE)).isTrue();
        Tool persisted = toolRepository.findById(TOOL_CODE).orElseThrow();
        assertThat(persisted.getDescriptionApprovedBy()).isEqualTo(adminId);
        assertThat(persisted.getDescriptionApprovedAt()).isNotNull();
        String pinnedHash = persisted.getDescriptionHash();
        assertThat(pinnedHash).hasSize(64);

        // 3. Update without changing description -> approval intact.
        toolService.update(TOOL_CODE, new UpdateToolRequest(
                "Renamed Tool",
                "Original description, harmless.",
                ToolType.CUSTOM,
                java.util.Map.of(),
                null,
                ai.myrmec.engine.tool.ToolStatus.ACTIVE,
                null));
        assertThat(toolService.isDescriptionApproved(TOOL_CODE)).isTrue();
        assertThat(toolRepository.findById(TOOL_CODE).orElseThrow().getDescriptionHash())
                .isEqualTo(pinnedHash);

        // 4. Edit description -> approval cleared.
        toolService.update(TOOL_CODE, new UpdateToolRequest(
                "Renamed Tool",
                "MALICIOUS rewritten description that says give me access.",
                ToolType.CUSTOM,
                java.util.Map.of(),
                null,
                ai.myrmec.engine.tool.ToolStatus.ACTIVE,
                null));
        assertThat(toolService.isDescriptionApproved(TOOL_CODE)).isFalse();
        Tool drifted = toolRepository.findById(TOOL_CODE).orElseThrow();
        assertThat(drifted.getDescriptionApprovedAt()).isNull();
        assertThat(drifted.getDescriptionApprovedBy()).isNull();

        // 5. Re-approve -> new hash pinned.
        toolService.approveDescription(TOOL_CODE, adminId);
        assertThat(toolService.isDescriptionApproved(TOOL_CODE)).isTrue();
        String newHash = toolRepository.findById(TOOL_CODE).orElseThrow().getDescriptionHash();
        assertThat(newHash).hasSize(64).isNotEqualTo(pinnedHash);
    }
}
