package ai.myrmec.engine._system.exception;

import lombok.Getter;

import java.util.List;

/**
 * Exception for when a resource cannot be deleted or modified because it is in use.
 * Returns HTTP 409 Conflict.
 */
@Getter
public class ResourceInUseException extends RuntimeException {

    private final List<ResourceInUseDetail> details;

    public ResourceInUseException(String message, List<ResourceInUseDetail> details) {
        super(message);
        this.details = details;
    }

    /**
     * Create an exception for a resource blocked by another resource type.
     */
    public static ResourceInUseException blockedBy(String resourceType, int count) {
        List<ResourceInUseDetail> details = List.of(
                ResourceInUseDetail.of(resourceType, true, count)
        );
        return new ResourceInUseException(
                "Resource is in use by " + count + " " + resourceType + "(s)",
                details
        );
    }
}
