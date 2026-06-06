package ai.myrmec.engine._system.exception;

import lombok.Builder;
import lombok.Getter;

/**
 * Detail for resource in use errors.
 */
@Getter
@Builder
public class ResourceInUseDetail {

    private final String resourceType;
    private final boolean blocking;
    private final int count;

    public static ResourceInUseDetail of(String resourceType, boolean blocking, int count) {
        return ResourceInUseDetail.builder()
                .resourceType(resourceType)
                .blocking(blocking)
                .count(count)
                .build();
    }
}
