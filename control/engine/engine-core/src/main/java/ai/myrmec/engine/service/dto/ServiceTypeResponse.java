package ai.myrmec.engine.service.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Read-only descriptor for a platform service type (#78). Combines the static
 * presentation metadata from {@link ai.myrmec.engine.service.ServiceType} with
 * the live count of projects that currently allow the type, so the
 * Platform → Service Types page can render without a second source of truth.
 *
 * <p>{@code enabled} is always {@code true} today; the platform-level
 * kill-switch (#79) will make it meaningful.</p>
 */
@Data
@Builder
public class ServiceTypeResponse {

    private String code;
    private String displayName;
    private String description;
    private String icon;
    private boolean enabled;
    private long projectEnabledCount;
}
