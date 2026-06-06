package ai.myrmec.engine.user;

import ai.myrmec.engine.user.dto.LoginRequest;
import ai.myrmec.engine.user.dto.LoginResponse;
import ai.myrmec.engine.user.dto.AuthProviderResponse;
import ai.myrmec.engine.user.dto.ExternalAuthStartResponse;
import ai.myrmec.engine.user.dto.UserRefreshRequest;
import ai.myrmec.engine.user.dto.UserRefreshResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for user authentication.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "User Authentication", description = "User login and token management")
public class UserAuthController {

    private final UserAuthService userAuthService;

    @PostMapping("/login")
    @Operation(summary = "Login with email and password")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = userAuthService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh user access token")
    public ResponseEntity<UserRefreshResponse> refresh(@Valid @RequestBody UserRefreshRequest request) {
        UserRefreshResponse response = userAuthService.refresh(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/providers/enabled")
    @Operation(summary = "List enabled authentication providers")
    public ResponseEntity<List<AuthProviderResponse>> listEnabledProviders() {
        List<AuthProviderResponse> response = userAuthService.getEnabledProviders().stream()
                .map(AuthProviderResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/external/{providerCode}/login")
    @Operation(summary = "Initiate external authentication flow")
    public ResponseEntity<ExternalAuthStartResponse> startExternalLogin(
            @PathVariable String providerCode,
            @RequestParam(required = false) String redirectUri) {
        ExternalAuthStartResponse response = userAuthService.startExternalLogin(providerCode, redirectUri);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/external/{providerCode}/callback")
    @Operation(summary = "Handle external authentication callback")
    public ResponseEntity<LoginResponse> externalCallback(
            @PathVariable String providerCode,
            @RequestParam String state,
            @RequestParam String code,
            @RequestParam(required = false) String redirectUri) {
        LoginResponse response = userAuthService.completeExternalLogin(providerCode, state, code, redirectUri);
        return ResponseEntity.ok(response);
    }
}
