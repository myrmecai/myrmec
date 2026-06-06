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
 * Global secrets — visible to every project, managed only by SYSTEM_ADMIN.
 *
 * <p>Lives under {@code /api/v1/admin/secrets} so existing
 * {@code SecurityConfig} ACL ({@code /api/v1/admin/**} requires
 * {@code ROLE_SYSTEM_ADMIN}) covers transport-level access; the
 * {@code @PreAuthorize} adds method-level defense in depth.
 */
@RestController
@RequestMapping("/api/v1/admin/secrets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class GlobalSecretController {

    private final SecretService secretService;

    @GetMapping
    public List<SecretResponse> list() {
        return secretService.listGlobal();
    }

    @GetMapping("/{id}")
    public SecretResponse findById(@PathVariable UUID id) {
        return secretService.findById(id);
    }

    @PostMapping
    public ResponseEntity<SecretResponse> create(
            @Valid @RequestBody CreateSecretRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        SecretResponse response = secretService.createGlobal(request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public SecretResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSecretRequest request) {
        return secretService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        secretService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
