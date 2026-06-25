package ai.myrmec.engine.service;

import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.service.dto.ServiceTypeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for the platform's service-type catalogue (#78).
 * Enumerates every {@link ServiceType} the platform ships with, decorates each
 * with its presentation metadata and the live count of projects that currently
 * allow it, and exposes the result as read-only descriptors for the
 * Platform → Service Types admin page.
 *
 * <p>Per-type enable/disable (the platform kill-switch) is intentionally out of
 * scope here and arrives with #79; every type reports {@code enabled = true}
 * for now.</p>
 */
@Component
@RequiredArgsConstructor
public class ServiceTypeRegistry {

    private final ProjectRepository projectRepository;

    /**
     * Returns one descriptor per shipped {@link ServiceType}, ordered by the
     * enum declaration order, each carrying the number of projects that allow
     * the type.
     */
    public List<ServiceTypeResponse> list() {
        Map<String, Long> counts = projectEnabledCounts();
        return Arrays.stream(ServiceType.values())
                .map(type -> ServiceTypeResponse.builder()
                        .code(type.name())
                        .displayName(type.displayName())
                        .description(type.description())
                        .icon(type.icon())
                        .enabled(true)
                        .projectEnabledCount(counts.getOrDefault(type.name(), 0L))
                        .build())
                .toList();
    }

    /**
     * Counts, per canonical service-type code, how many projects list the type
     * in their {@code allowed_service_types}. Computed in-memory because the
     * list is stored as a JSON-text column (H2/Postgres parity) rather than a
     * queryable native array, and the project count is admin-scale.
     */
    private Map<String, Long> projectEnabledCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (Project project : projectRepository.findAll()) {
            List<String> allowed = project.getAllowedServiceTypes();
            if (allowed == null) {
                continue;
            }
            for (String raw : allowed) {
                ServiceType.fromString(raw)
                        .ifPresent(type -> counts.merge(type.name(), 1L, Long::sum));
            }
        }
        return counts;
    }
}
