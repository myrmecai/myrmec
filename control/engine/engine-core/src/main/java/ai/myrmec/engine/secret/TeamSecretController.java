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
 * Team-scoped secrets — managed by EDITORs of the Team (or SYSTEM_ADMIN).
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/secrets")
@RequiredArgsConstructor
public class TeamSecretController {

    private final SecretService secretService;

    @GetMapping
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public List<SecretResponse> list(@PathVariable UUID teamId) {
        return secretService.listForTeam(teamId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public SecretResponse findById(@PathVariable UUID teamId, @PathVariable UUID id) {
        return secretService.findById(id);
    }

    @PostMapping
    @PreAuthorize("@teamAccess.canEdit(#teamId, authentication)")
    public ResponseEntity<SecretResponse> create(
            @PathVariable UUID teamId,
            @Valid @RequestBody CreateSecretRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        SecretResponse response = secretService.createForTeam(teamId, request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@teamAccess.canEdit(#teamId, authentication)")
    public SecretResponse update(
            @PathVariable UUID teamId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSecretRequest request) {
        return secretService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@teamAccess.canEdit(#teamId, authentication)")
    public ResponseEntity<Void> delete(@PathVariable UUID teamId, @PathVariable UUID id) {
        secretService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
