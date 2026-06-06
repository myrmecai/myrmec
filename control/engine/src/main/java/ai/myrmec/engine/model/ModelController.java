package ai.myrmec.engine.model;

import ai.myrmec.engine.model.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for model management.
 * Admin endpoints are under /api/v1/admin/models
 * Public endpoints are under /api/v1/models
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Models", description = "AI model configuration management")
public class ModelController {

    private final ModelService modelService;
    private final ModelProviderConfigRepository providerRepository;

    // ==================== Admin Endpoints ====================

    @GetMapping("/api/v1/admin/models")
    @Operation(summary = "List all models (admin)")
    public ResponseEntity<List<ModelResponse>> listAllModels() {
        List<Model> models = modelService.findAll();
        List<ModelResponse> response = models.stream()
                .map(ModelResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/admin/models/{code}")
    @Operation(summary = "Get model by code (admin)")
    public ResponseEntity<ModelResponse> getModel(@PathVariable String code) {
        Model model = modelService.findByCode(code);
        return ResponseEntity.ok(ModelResponse.from(model));
    }

    @PostMapping("/api/v1/admin/models")
    @Operation(summary = "Create a new model")
    public ResponseEntity<ModelResponse> createModel(
            @Valid @RequestBody CreateModelRequest request) {
        Model model = modelService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ModelResponse.from(model));
    }

    @PutMapping("/api/v1/admin/models/{code}")
    @Operation(summary = "Update model details")
    public ResponseEntity<ModelResponse> updateModel(
            @PathVariable String code,
            @Valid @RequestBody UpdateModelRequest request) {
        Model model = modelService.update(code, request);
        return ResponseEntity.ok(ModelResponse.from(model));
    }

    @DeleteMapping("/api/v1/admin/models/{code}")
    @Operation(summary = "Delete a model")
    public ResponseEntity<Void> deleteModel(@PathVariable String code) {
        modelService.delete(code);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/models/{code}/test")
    @Operation(summary = "Test model connection")
    public ResponseEntity<TestModelResponse> testModelConnection(@PathVariable String code) {
        TestModelResponse response = modelService.testConnection(code);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/admin/models/{code}/health")
    @Operation(summary = "Get model health status")
    public ResponseEntity<ModelHealthResponse> getModelHealth(@PathVariable String code) {
        Model model = modelService.findByCode(code);
        
        // For now, return basic health info. Full implementation would include metrics and history.
        ModelHealthResponse response = ModelHealthResponse.builder()
                .code(model.getCode())
                .status(model.getHealthStatus())
                .lastCheck(model.getLastHealthCheck())
                .build();
        
        return ResponseEntity.ok(response);
    }

    // ==================== Public Endpoints ====================

    @GetMapping("/api/v1/models")
    @Operation(summary = "List active models (all users, no credentials)")
    public ResponseEntity<List<ModelResponse>> listActiveModels() {
        List<Model> models = modelService.findAllActive();
        List<ModelResponse> response = models.stream()
                .map(ModelResponse::fromPublic)
                .toList();
        return ResponseEntity.ok(response);
    }

    // ==================== Provider Endpoints ====================

    @GetMapping("/api/v1/providers")
    @Operation(summary = "List all active providers")
    public ResponseEntity<List<ModelProviderResponse>> listProviders() {
        List<ModelProviderConfig> providers = providerRepository.findAllActive();
        List<ModelProviderResponse> response = providers.stream()
                .map(ModelProviderResponse::fromPublic)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/providers/{code}")
    @Operation(summary = "Get provider by code")
    public ResponseEntity<ModelProviderResponse> getProvider(@PathVariable String code) {
        return providerRepository.findById(code)
                .map(ModelProviderResponse::fromPublic)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/v1/admin/providers")
    @Operation(summary = "List all providers (admin)")
    public ResponseEntity<List<ModelProviderResponse>> listAllProviders() {
        List<ModelProviderConfig> providers = providerRepository.findAll();
        List<ModelProviderResponse> response = providers.stream()
                .map(ModelProviderResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/admin/providers/{code}")
    @Operation(summary = "Get provider details (admin)")
    public ResponseEntity<ModelProviderResponse> getProviderAdmin(@PathVariable String code) {
        return providerRepository.findById(code)
                .map(ModelProviderResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
