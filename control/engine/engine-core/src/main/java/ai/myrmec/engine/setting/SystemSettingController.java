package ai.myrmec.engine.setting;

import ai.myrmec.engine.setting.dto.SystemSettingResponse;
import ai.myrmec.engine.setting.dto.UpdateSystemSettingRequest;
import ai.myrmec.engine.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * #71a &mdash; admin surface for the platform System Settings store.
 * Restricted to {@code PLATFORM_ADMIN}: these keys gate engine-wide
 * behaviour. Settings are provisioned by migrations, so the surface is
 * list + update only (no create/delete).
 */
@RestController
@RequestMapping("/api/v1/admin/system-settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@Tag(name = "System Settings", description = "Platform-level typed configuration store")
public class SystemSettingController {

    private final SystemSettingService service;

    @Operation(summary = "List all platform settings (alphabetical by key)")
    @GetMapping
    public List<SystemSettingResponse> list() {
        return service.findAll().stream()
                .map(SystemSettingResponse::from)
                .toList();
    }

    @Operation(summary = "Update a setting's value (blank resets to default)")
    @PutMapping("/{key}")
    public SystemSettingResponse update(
            @PathVariable String key,
            @Valid @RequestBody UpdateSystemSettingRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        SystemSetting saved = service.update(
                key,
                request.value(),
                principal != null ? principal.getUserId() : null);
        return SystemSettingResponse.from(saved);
    }
}
