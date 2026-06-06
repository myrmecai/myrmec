package ai.myrmec.engine._system.exception;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Exception for validation errors and bad requests.
 * Supports field-level validation details.
 */
@Getter
public class BadRequestException extends RuntimeException {

    private final List<ValidationDetail> details;

    public BadRequestException(String message) {
        super(message);
        this.details = new ArrayList<>();
    }

    public BadRequestException(String message, List<ValidationDetail> details) {
        super(message);
        this.details = details != null ? details : new ArrayList<>();
    }

    /**
     * Create a BadRequestException with a single field error.
     */
    public static BadRequestException forField(String field, String errorCode, String message) {
        List<ValidationDetail> details = List.of(ValidationDetail.of(field, errorCode, message));
        return new BadRequestException("Validation error for field: " + field, details);
    }

    /**
     * Create a BadRequestException for a missing required field.
     */
    public static BadRequestException requiredField(String field) {
        List<ValidationDetail> details = List.of(ValidationDetail.required(field));
        return new BadRequestException("Required field missing: " + field, details);
    }

    public boolean hasDetails() {
        return details != null && !details.isEmpty();
    }
}
