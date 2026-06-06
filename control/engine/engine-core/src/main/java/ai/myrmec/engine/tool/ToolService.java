package ai.myrmec.engine.tool;

import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.tool.dto.CreateToolRequest;
import ai.myrmec.engine.tool.dto.ToolResponse;
import ai.myrmec.engine.tool.dto.UpdateToolRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

        return toResponse(toolRepository.save(tool));
    }

    @Transactional
    public ToolResponse update(String code, UpdateToolRequest request) {
        Tool tool = toolRepository.findById(code)
                .orElseThrow(() -> new ResourceNotFoundException("Tool", code));

        tool.setName(request.name());
        tool.setDescription(request.description());
        tool.setToolType(request.toolType());
        tool.setConfigSchema(request.configSchema());
        tool.setDocsUrl(request.docsUrl());
        tool.setStatus(request.status());

        return toResponse(toolRepository.save(tool));
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
                tool.getCreatedAt(),
                tool.getUpdatedAt()
        );
    }
}
