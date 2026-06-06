package ai.myrmec.engine.secret;

import ai.myrmec.engine.secret.dto.CreateSecretRequest;
import ai.myrmec.engine.secret.dto.SecretResponse;
import ai.myrmec.engine.secret.dto.UpdateSecretRequest;
import ai.myrmec.engine.user.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Project-scoped secrets — managed by EDITORs of the project (or SYSTEM_ADMIN).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/secrets")
@RequiredArgsConstructor
public class ProjectSecretController {

    private final SecretService secretService;

    @GetMapping
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public List<SecretResponse> list(@PathVariable UUID projectId) {
        return secretService.listForProject(projectId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public SecretResponse findById(@PathVariable UUID projectId, @PathVariable UUID id) {
        return secretService.findById(id);
    }

    @PostMapping
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<SecretResponse> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateSecretRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        SecretResponse response = secretService.createForProject(projectId, request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public SecretResponse update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSecretRequest request) {
        return secretService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID id) {
        secretService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
