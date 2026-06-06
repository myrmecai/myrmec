package ai.myrmec.engine._system.exception;

import lombok.Builder;
import lombok.Getter;

/**
 * Detail for resource not found errors.
 */
@Getter
@Builder
public class ResourceDetail {

    private final String resourceType;
    private final String identifier;

    public static ResourceDetail of(String resourceType, String identifier) {
        return ResourceDetail.builder()
                .resourceType(resourceType)
                .identifier(identifier)
                .build();
    }
}
