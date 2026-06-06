package ai.myrmec.engine.tool;

import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.tool.dto.CreateToolRequest;
import ai.myrmec.engine.tool.dto.ToolResponse;
import ai.myrmec.engine.tool.dto.UpdateToolRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ToolService {

    private final ToolRepository toolRepository;

    @Transactional(readOnly = true)
    public List<ToolResponse> findAll() {
        return toolRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ToolResponse> findActive() {
        return toolRepository.findByStatus(ToolStatus.ACTIVE).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ToolResponse findByCode(String code) {
        return toolRepository.findById(code)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Tool", code));
    }

    @Transactional
    public ToolResponse create(CreateToolRequest request) {
        if (toolRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException("Tool", "code", request.code());
        }

        Tool tool = new Tool();
        tool.setCode(request.code());
        tool.setName(request.name());
        tool.setDescription(request.description());
        tool.setToolType(request.toolType());
        tool.setConfigSchema(request.configSchema());
        tool.setDocsUrl(request.docsUrl());
        tool.setSystem(false);
        tool.setStatus(ToolStatus.ACTIVE);
        // Default to SAFE so callers who don't supply a risk class get
        // the (HITL-bypass) behaviour they had pre-Phase 7. Conscious
        // classification is required to opt a tool in to HITL.
        tool.setRiskClass(request.riskClass() != null ? request.riskClass() : RiskClass.SAFE);
        // Phase 9f — first save records "drift" (no approval) until an
        // admin explicitly approves the description. UIs should surface
        // a "needs review" badge so this never goes unnoticed.

        return toResponse(toolRepository.save(tool));
    }

    @Transactional
    public ToolResponse update(String code, UpdateToolRequest request) {
        Tool tool = toolRepository.findById(code)
                .orElseThrow(() -> new ResourceNotFoundException("Tool", code));

        // Phase 9f — clear approval if description has changed. If the
        // admin just renamed/re-categorised but kept the description,
        // the existing approval still holds.
        String newHash = sha256Hex(request.description());
        if (!Objects.equals(newHash, tool.getDescriptionHash())
                && !Objects.equals(tool.getDescriptionApprovedAt(), null)) {
            tool.setDescriptionApprovedAt(null);
            tool.setDescriptionApprovedBy(null);
        }

        tool.setName(request.name());
        tool.setDescription(request.description());
        tool.setToolType(request.toolType());
        tool.setConfigSchema(request.configSchema());
        tool.setDocsUrl(request.docsUrl());
        tool.setStatus(request.status());
        if (request.riskClass() != null) {
            // Null means "leave classification alone" — see UpdateToolRequest.
            tool.setRiskClass(request.riskClass());
        }

        return toResponse(toolRepository.save(tool));
    }

    /**
     * Phase 9f — admin re-approves the current description. Pins the
     * SHA-256 of {@link Tool#getDescription()} alongside the actor and
     * timestamp; subsequent description edits will clear the approval
     * automatically. Caller must enforce its own AuthZ
     * (PLATFORM_ADMIN).
     *
     * @return updated {@link ToolResponse}.
     */
    @Transactional
    public ToolResponse approveDescription(String code, UUID approverUserId) {
        Tool tool = toolRepository.findById(code)
                .orElseThrow(() -> new ResourceNotFoundException("Tool", code));
        tool.setDescriptionHash(sha256Hex(tool.getDescription()));
        tool.setDescriptionApprovedAt(Instant.now());
        tool.setDescriptionApprovedBy(approverUserId);
        return toResponse(toolRepository.save(tool));
    }

    /**
     * Phase 9f — drift check used by callers that wire tool descriptions
     * into a model prompt. Returns {@code true} when the live
     * description SHA-256 matches the pinned hash and an admin has
     * approved it. {@code false} means: never approved, hash drifted,
     * or description was edited after approval.
     */
    @Transactional(readOnly = true)
    public boolean isDescriptionApproved(String code) {
        Tool tool = toolRepository.findById(code)
                .orElseThrow(() -> new ResourceNotFoundException("Tool", code));
        if (tool.getDescriptionHash() == null || tool.getDescriptionApprovedAt() == null) {
            return false;
        }
        return Objects.equals(sha256Hex(tool.getDescription()), tool.getDescriptionHash());
    }

    @Transactional
    public void delete(String code) {
        Tool tool = toolRepository.findById(code)
                .orElseThrow(() -> new ResourceNotFoundException("Tool", code));

        if (tool.isSystem()) {
            throw new IllegalStateException("System tools cannot be deleted");
        }

        // TODO: Check if tool is in use by any agent profiles before deletion

        toolRepository.delete(tool);
    }

    /** SHA-256 hex of UTF-8 bytes; null in → null out. */
    static String sha256Hex(String value) {
        if (value == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Java guarantees SHA-256 is available; this can never fire.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private ToolResponse toResponse(Tool tool) {
        return new ToolResponse(
                tool.getCode(),
                tool.getName(),
                tool.getDescription(),
                tool.getToolType(),
                tool.getConfigSchema(),
                tool.getDocsUrl(),
                tool.isSystem(),
                tool.getStatus(),
                tool.getRiskClass(),
                tool.getCreatedAt(),
                tool.getUpdatedAt()
        );
    }
}
