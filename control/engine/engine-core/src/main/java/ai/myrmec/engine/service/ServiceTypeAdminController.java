package ai.myrmec.engine.service;

import ai.myrmec.engine.service.dto.ServiceTypeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only admin endpoint backing the Platform → Service Types page (#78).
 * Lists every platform service type with its descriptor and the number of
 * projects that currently allow it. There is no mutation surface yet — the
 * platform kill-switch (#79) and group defaults (#80) build on this.
 */
@RestController
@RequestMapping("/api/v1/admin/service-types")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
public class ServiceTypeAdminController {

    private final ServiceTypeRegistry serviceTypeRegistry;

    @GetMapping
    public List<ServiceTypeResponse> list() {
        return serviceTypeRegistry.list();
    }
}
