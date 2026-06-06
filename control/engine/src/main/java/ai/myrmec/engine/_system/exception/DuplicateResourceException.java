package ai.myrmec.engine._system.exception;

import lombok.Getter;

/**
 * Exception for when a resource with the same unique field already exists.
 */
@Getter
public class DuplicateResourceException extends RuntimeException {

    private final String resourceType;
    private final String field;
    private final Object value;

    public DuplicateResourceException(String resourceType, String field, Object value) {
        super(String.format("%s with %s '%s' already exists", resourceType, field, value));
        this.resourceType = resourceType;
        this.field = field;
        this.value = value;
    }
}
