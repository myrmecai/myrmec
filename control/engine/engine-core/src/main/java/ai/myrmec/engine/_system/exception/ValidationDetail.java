package ai.myrmec.engine._system.exception;

import lombok.Builder;
import lombok.Getter;

/**
 * Detail for a single field validation error.
 */
@Getter
@Builder
public class ValidationDetail {

    private final String field;
    private final String errorCode;
    private final String message;

    public static ValidationDetail of(String field, String errorCode, String message) {
        return ValidationDetail.builder()
                .field(field)
                .errorCode(errorCode)
                .message(message)
                .build();
    }

    /**
     * Create a validation detail for a required field.
     */
    public static ValidationDetail required(String field) {
        String fieldName = formatFieldName(field);
        return of(field, "REQUIRED", fieldName + " is required.");
    }

    /**
     * Create a validation detail for an invalid format.
     */
    public static ValidationDetail invalidFormat(String field, String expectedFormat) {
        String fieldName = formatFieldName(field);
        return of(field, "INVALID_FORMAT", fieldName + " must be in format " + expectedFormat + ".");
    }

    private static String formatFieldName(String field) {
        if (field == null || field.isEmpty()) {
            return "Field";
        }
        // Convert camelCase to readable format, capitalize first letter
        String name = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
