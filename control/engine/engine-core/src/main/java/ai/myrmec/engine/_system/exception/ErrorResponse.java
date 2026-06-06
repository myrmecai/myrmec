package ai.myrmec.engine._system.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Standard error response format for all API errors.
 * Supports both simple errors and errors with structured details.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String errorCode;
    private final String message;
    private final Object details;

    /**
     * Create a simple error response without details.
     */
    public static ErrorResponse of(String errorCode, String message) {
        return ErrorResponse.builder()
                .errorCode(errorCode)
                .message(message)
                .build();
    }

    /**
     * Create an error response with validation details.
     */
    public static ErrorResponse validation(List<ValidationDetail> details) {
        return ErrorResponse.builder()
                .errorCode("VALIDATION_ERROR")
                .message("One or more validation errors occurred.")
                .details(details)
                .build();
    }

    /**
     * Create an error response for resource not found.
     */
    public static ErrorResponse resourceNotFound(String resourceType, String identifier) {
        return ErrorResponse.builder()
                .errorCode("RESOURCE_NOT_FOUND")
                .message("The requested resource was not found.")
                .details(ResourceDetail.of(resourceType, identifier))
                .build();
    }

    /**
     * Create an error response for resource in use.
     */
    public static ErrorResponse resourceInUse(List<ResourceInUseDetail> details) {
        return ErrorResponse.builder()
                .errorCode("RESOURCE_IN_USE")
                .message("The resource cannot be deleted because it is still in use.")
                .details(details)
                .build();
    }
}
