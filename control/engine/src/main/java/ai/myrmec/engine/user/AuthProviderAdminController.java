package ai.myrmec.engine.user;

import ai.myrmec.engine.user.dto.AuthProviderCreateRequest;
import ai.myrmec.engine.user.dto.AuthProviderResponse;
import ai.myrmec.engine.user.dto.AuthProviderSecretReencryptResponse;
import ai.myrmec.engine.user.dto.AuthProviderUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/auth-providers")
@RequiredArgsConstructor
@Tag(name = "Authentication Provider Management", description = "Admin operations for authentication providers")
public class AuthProviderAdminController {

    private final AuthProviderService authProviderService;

    @GetMapping
    @Operation(summary = "List configured authentication providers")
    public ResponseEntity<List<AuthProviderResponse>> list() {
        List<AuthProviderResponse> response = authProviderService.findAll().stream()
                .map(AuthProviderResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @Operation(summary = "Create authentication provider")
    public ResponseEntity<AuthProviderResponse> create(@Valid @RequestBody AuthProviderCreateRequest request) {
        AuthenticationProvider provider = authProviderService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthProviderResponse.from(provider));
    }

    @PatchMapping("/{code}")
    @Operation(summary = "Update authentication provider")
    public ResponseEntity<AuthProviderResponse> update(
            @PathVariable String code,
            @Valid @RequestBody AuthProviderUpdateRequest request) {
        AuthenticationProvider provider = authProviderService.update(code, request);
        return ResponseEntity.ok(AuthProviderResponse.from(provider));
    }

    @PostMapping("/{code}/enable")
    @Operation(summary = "Enable authentication provider")
    public ResponseEntity<AuthProviderResponse> enable(@PathVariable String code) {
        AuthenticationProvider provider = authProviderService.enable(code);
        return ResponseEntity.ok(AuthProviderResponse.from(provider));
    }

    @PostMapping("/{code}/disable")
    @Operation(summary = "Disable authentication provider")
    public ResponseEntity<AuthProviderResponse> disable(@PathVariable String code) {
        AuthenticationProvider provider = authProviderService.disable(code);
        return ResponseEntity.ok(AuthProviderResponse.from(provider));
    }

    @PostMapping("/{code}/validate")
    @Operation(summary = "Validate authentication provider configuration")
    public ResponseEntity<AuthProviderResponse> validate(@PathVariable String code) {
        AuthenticationProvider provider = authProviderService.findByCode(code);
        return ResponseEntity.ok(AuthProviderResponse.from(provider));
    }

    @PostMapping("/reencrypt-secrets")
    @Operation(summary = "Re-encrypt stored auth provider client secrets with active encryption key")
    public ResponseEntity<AuthProviderSecretReencryptResponse> reencryptSecrets() {
        AuthProviderService.ReencryptSecretsResult result = authProviderService.reencryptStoredClientSecrets();
        return ResponseEntity.ok(AuthProviderSecretReencryptResponse.from(result));
    }
}
