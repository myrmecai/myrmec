package ai.myrmec.engine.tool;

import ai.myrmec.engine.tool.dto.CreateToolRequest;
import ai.myrmec.engine.tool.dto.ToolResponse;
import ai.myrmec.engine.tool.dto.UpdateToolRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin endpoints for managing tool registry.
 */
@RestController
@RequestMapping("/api/v1/admin/tools")
@RequiredArgsConstructor
public class ToolAdminController {

    private final ToolService toolService;

    @GetMapping
    public List<ToolResponse> findAll() {
        return toolService.findAll();
    }

    @GetMapping("/active")
    public List<ToolResponse> findActive() {
        return toolService.findActive();
    }

    @GetMapping("/{code}")
    public ToolResponse findByCode(@PathVariable String code) {
        return toolService.findByCode(code);
    }

    @PostMapping
    public ResponseEntity<ToolResponse> create(@Valid @RequestBody CreateToolRequest request) {
        ToolResponse response = toolService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{code}")
    public ToolResponse update(@PathVariable String code, @Valid @RequestBody UpdateToolRequest request) {
        return toolService.update(code, request);
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        toolService.delete(code);
        return ResponseEntity.noContent().build();
    }
}
