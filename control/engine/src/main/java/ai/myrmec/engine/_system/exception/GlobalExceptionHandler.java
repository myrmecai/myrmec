package ai.myrmec.engine._system.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Global exception handler for consistent error responses.
 * All errors return a structured ErrorResponse with error codes for localization.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ==================== Security Exceptions ====================

    @ExceptionHandler(InvalidRegistrationKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRegistrationKey(InvalidRegistrationKeyException ex) {
        // Log with masked key for debugging
        log.warn("Invalid registration key attempt: {}", ex.getMessage());
        // Return generic message to prevent enumeration
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_CREDENTIALS", "Invalid or expired credentials."));
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex) {
        log.warn("Invalid token: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_TOKEN", "Invalid or expired token."));
    }

    // ==================== Business Exceptions ====================

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        
        if (ex.hasDetails()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.validation(ex.getDetails()));
        }
        
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {} ({})", ex.getResourceType(), ex.getIdentifier());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.resourceNotFound(ex.getResourceType(), ex.getIdentifier()));
    }

    @ExceptionHandler(ResourceInUseException.class)
    public ResponseEntity<ErrorResponse> handleResourceInUse(ResourceInUseException ex) {
        log.warn("Resource in use: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.resourceInUse(ex.getDetails()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResource(DuplicateResourceException ex) {
        log.warn("Duplicate resource: {} {} = {}", ex.getResourceType(), ex.getField(), ex.getValue());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_CODE",
                        String.format("%s with %s '%s' already exists.", ex.getResourceType(), ex.getField(), ex.getValue())));
    }

    // ==================== Validation Exceptions ====================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ValidationDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> ValidationDetail.of(
                        error.getField(),
                        mapConstraintToErrorCode(error.getCode()),
                        error.getDefaultMessage()
                ))
                .collect(Collectors.toList());

        log.warn("Validation failed: {} error(s)", details.size());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.validation(details));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(ai.myrmec.engine.secret.SecretTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleSecretTypeMismatch(ai.myrmec.engine.secret.SecretTypeMismatchException ex) {
        log.warn("Secret type mismatch: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("SECRET_TYPE_MISMATCH", ex.getMessage()));
    }

    // ==================== Technical Exceptions ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        // Log full stack trace for technical errors
        log.error("Unexpected error occurred", ex);
        // Return generic message to client to avoid leaking internal details
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred. Please try again later."));
    }

    // ==================== Helper Methods ====================

    /**
     * Map JSR-380 constraint annotation names to error codes.
     */
    private String mapConstraintToErrorCode(String constraintName) {
        if (constraintName == null) {
            return "INVALID";
        }
        return switch (constraintName) {
            case "NotNull", "NotEmpty", "NotBlank" -> "REQUIRED";
            case "Size" -> "SIZE";
            case "Pattern" -> "INVALID_FORMAT";
            case "Email" -> "INVALID_EMAIL";
            case "Min", "Max" -> "OUT_OF_RANGE";
            case "Past", "Future", "PastOrPresent", "FutureOrPresent" -> "INVALID_DATE";
            default -> constraintName.toUpperCase();
        };
    }
}
