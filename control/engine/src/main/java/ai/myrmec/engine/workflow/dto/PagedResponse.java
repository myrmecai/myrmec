package ai.myrmec.engine.workflow.dto;

import java.util.List;

/**
 * Lightweight paged response shape returned by list endpoints.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PagedResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / (double) size);
        return new PagedResponse<>(content, page, size, totalElements, totalPages);
    }
}
