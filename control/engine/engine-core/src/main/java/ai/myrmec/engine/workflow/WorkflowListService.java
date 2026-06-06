package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserRoleRepository;
import ai.myrmec.engine.workflow.dto.AccessibleProjectResponse;
import ai.myrmec.engine.workflow.dto.PagedResponse;
import ai.myrmec.engine.workflow.dto.WorkflowListItemResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only service backing the cross-project workflow list view.
 *
 * <p>Computes the set of projects the calling user can access, then performs a
 * filtered, sorted, paged query joining the most recent {@link WorkflowRequest}
 * for each workflow to expose last-run summary fields.
 */
@Service
@RequiredArgsConstructor
public class WorkflowListService {

    /** Sort field aliases exposed to API clients, mapped to JPQL expressions. */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "name", "w.name",
            "status", "w.status",
            "projectName", "p.name",
            "createdAt", "w.createdAt",
            "updatedAt", "w.updatedAt",
            "lastRunAt", "lr.createdAt"
    );

    private static final int MAX_PAGE_SIZE = 200;

    @PersistenceContext
    private EntityManager entityManager;

    private final UserRoleRepository userRoleRepository;

    @Transactional(readOnly = true)
    public PagedResponse<WorkflowListItemResponse> list(UUID userId, Filter filter, Pageable pageable) {
        AccessScope scope = resolveAccessScope(userId);
        if (!scope.unrestricted && scope.projectIds.isEmpty()) {
            return PagedResponse.of(List.of(), pageable.page(), pageable.size(), 0L);
        }

        String sortExpr = SORT_FIELDS.get(pageable.sort());
        if (sortExpr == null) {
            throw new BadRequestException("Invalid sort field: " + pageable.sort());
        }
        String sortDir = pageable.direction().equalsIgnoreCase("asc") ? "ASC" : "DESC";

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        Map<String, Object> params = new HashMap<>();

        if (!scope.unrestricted) {
            where.append(" AND p.id IN :scopeProjectIds ");
            params.put("scopeProjectIds", scope.projectIds);
        }

        if (filter.projectIds != null && !filter.projectIds.isEmpty()) {
            where.append(" AND p.id IN :filterProjectIds ");
            params.put("filterProjectIds", filter.projectIds);
        }

        if (filter.statuses != null && !filter.statuses.isEmpty()) {
            where.append(" AND w.status IN :statuses ");
            params.put("statuses", filter.statuses);
        }

        if (filter.search != null && !filter.search.isBlank()) {
            where.append(" AND LOWER(w.name) LIKE LOWER(CONCAT('%', :search, '%')) ");
            params.put("search", filter.search.trim());
        }

        if (filter.createdFrom != null) {
            where.append(" AND w.createdAt >= :createdFrom ");
            params.put("createdFrom", filter.createdFrom);
        }
        if (filter.createdTo != null) {
            where.append(" AND w.createdAt <= :createdTo ");
            params.put("createdTo", filter.createdTo);
        }

        if (filter.lastRunFrom != null) {
            where.append(" AND lr.createdAt >= :lastRunFrom ");
            params.put("lastRunFrom", filter.lastRunFrom);
        }
        if (filter.lastRunTo != null) {
            where.append(" AND lr.createdAt <= :lastRunTo ");
            params.put("lastRunTo", filter.lastRunTo);
        }

        boolean hasRunStatusFilter = filter.lastRunStatuses != null && !filter.lastRunStatuses.isEmpty();
        if (hasRunStatusFilter && filter.includeNeverRun) {
            where.append(" AND (lr.status IN :runStatuses OR lr.id IS NULL) ");
            params.put("runStatuses", filter.lastRunStatuses);
        } else if (hasRunStatusFilter) {
            where.append(" AND lr.status IN :runStatuses ");
            params.put("runStatuses", filter.lastRunStatuses);
        } else if (filter.includeNeverRun) {
            // "never" was the only run-status entry selected
            where.append(" AND lr.id IS NULL ");
        }

        String joinClause = """
                FROM Workflow w
                JOIN w.project p
                JOIN w.createdBy cb
                LEFT JOIN WorkflowRequest lr ON lr.workflow.id = w.id
                    AND lr.createdAt = (
                        SELECT MAX(r.createdAt) FROM WorkflowRequest r
                        WHERE r.workflow.id = w.id
                    )
                """;

        String selectJpql = "SELECT new ai.myrmec.engine.workflow.dto.WorkflowListItemResponse("
                + "w.id, w.name, w.description, w.version, w.status, "
                + "p.id, p.name, cb.email, w.createdAt, w.updatedAt, "
                + "lr.id, lr.status, lr.createdAt) "
                + joinClause
                + where
                + " ORDER BY " + sortExpr + " " + sortDir
                + ", w.id ASC ";

        String countJpql = "SELECT COUNT(w) " + joinClause + where;

        TypedQuery<WorkflowListItemResponse> query = entityManager.createQuery(selectJpql, WorkflowListItemResponse.class);
        params.forEach(query::setParameter);
        int page = Math.max(0, pageable.page());
        int size = Math.min(MAX_PAGE_SIZE, Math.max(1, pageable.size()));
        query.setFirstResult(page * size);
        query.setMaxResults(size);
        List<WorkflowListItemResponse> content = query.getResultList();

        Query countQuery = entityManager.createQuery(countJpql);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        return PagedResponse.of(content, page, size, total);
    }

    @Transactional(readOnly = true)
    public List<AccessibleProjectResponse> accessibleProjects(UUID userId) {
        AccessScope scope = resolveAccessScope(userId);

        String jpql;
        Map<String, Object> params = new HashMap<>();
        if (scope.unrestricted) {
            jpql = "SELECT new ai.myrmec.engine.workflow.dto.AccessibleProjectResponse(p.id, p.name) "
                    + "FROM Project p ORDER BY LOWER(p.name) ASC";
        } else {
            if (scope.projectIds.isEmpty()) {
                return List.of();
            }
            jpql = "SELECT new ai.myrmec.engine.workflow.dto.AccessibleProjectResponse(p.id, p.name) "
                    + "FROM Project p WHERE p.id IN :ids ORDER BY LOWER(p.name) ASC";
            params.put("ids", scope.projectIds);
        }
        TypedQuery<AccessibleProjectResponse> q = entityManager.createQuery(jpql, AccessibleProjectResponse.class);
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    private AccessScope resolveAccessScope(UUID userId) {
        List<UserRole> roles = userRoleRepository.findByUserId(userId);
        boolean unrestricted = roles.stream()
                .anyMatch(r -> r.getProjectId() == null);
        Set<UUID> projectIds = roles.stream()
                .map(UserRole::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return new AccessScope(unrestricted, projectIds);
    }

    private record AccessScope(boolean unrestricted, Set<UUID> projectIds) {}

    /**
     * Filter parameters for the list query. All fields are optional.
     */
    public record Filter(
            Set<WorkflowStatus> statuses,
            Set<UUID> projectIds,
            String search,
            Set<RequestStatus> lastRunStatuses,
            boolean includeNeverRun,
            Instant createdFrom,
            Instant createdTo,
            Instant lastRunFrom,
            Instant lastRunTo
    ) {}

    /**
     * Page + sort spec for the list query.
     */
    public record Pageable(int page, int size, String sort, String direction) {}
}
