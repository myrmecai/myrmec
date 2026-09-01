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

    @ExceptionHandler(ai.myrmec.engine.knowledge.RetrievalProviderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRetrievalProviderNotFound(
            ai.myrmec.engine.knowledge.RetrievalProviderNotFoundException ex) {
        log.warn("Retrieval provider not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("RESOURCE_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ai.myrmec.engine.knowledge.RetrievalForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleRetrievalForbidden(
            ai.myrmec.engine.knowledge.RetrievalForbiddenException ex) {
        log.warn("Retrieval forbidden: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", ex.getMessage()));
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

    @ExceptionHandler(DraftConflictException.class)
    public ResponseEntity<ErrorResponse> handleDraftConflict(DraftConflictException ex) {
        log.warn("Draft conflict ({}): {}", ex.getKind(), ex.getMessage());
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("kind", ex.getKind().name());
        if (ex.getDraftId() != null) {
            details.put("draftId", ex.getDraftId());
        }
        if (ex.getDraftOwnerId() != null) {
            details.put("draftOwnerId", ex.getDraftOwnerId());
        }
        if (ex.getDraftStartedAt() != null) {
            details.put("draftStartedAt", ex.getDraftStartedAt());
        }
        if (ex.getCurrentVersionNumber() != null) {
            details.put("currentVersionNumber", ex.getCurrentVersionNumber());
        }
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.builder()
                        .errorCode("DRAFT_CONFLICT")
                        .message(ex.getMessage())
                        .details(details)
                        .build());
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

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuotaExceeded(QuotaExceededException ex) {
        log.warn("Quota exceeded: {}", ex.getMessage());
        ErrorResponse body = ErrorResponse.of(
                "QUOTA_EXCEEDED",
                String.format(
                        "%s quota exceeded at %s scope (used %d of %d).",
                        ex.getResourceType(), ex.getScope(),
                        ex.getConsumedAmount(), ex.getLimitAmount()));
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(body);
    }

    /**
     * Surface Spring Security's authorisation failures as 403 rather than
     * letting them fall through to the generic handler (which would turn
     * a routine permission denial into a 500 + alert noise).
     */
    @ExceptionHandler({
            org.springframework.security.authorization.AuthorizationDeniedException.class,
            org.springframework.security.access.AccessDeniedException.class
    })
    public ResponseEntity<ErrorResponse> handleAccessDenied(Exception ex) {
        log.debug("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", "Access denied"));
    }

    // ==================== Governance ====================

    @ExceptionHandler(ai.myrmec.engine.governance.GovernanceViolationException.class)
    public ResponseEntity<ErrorResponse> handleGovernanceViolation(
            ai.myrmec.engine.governance.GovernanceViolationException ex) {
        log.warn("Governance violation: {} (feature={}, attempted={})",
                ex.getMessage(), ex.getFeature().name(), ex.getAttemptedValue());
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("feature", ex.getFeature().name());
        details.put("attemptedValue", ex.getAttemptedValue());
        details.put("allowedValues", ex.getAllowedValues());
        details.put("profileCode", ex.getProfileCode());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.builder()
                        .errorCode("GOVERNANCE_VIOLATION")
                        .message(ex.getMessage())
                        .details(details)
                        .build());
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

    /**
     * Handle errors that occur during an SSE stream response. The response
     * is already committed as {@code text/event-stream}, so we cannot write
     * a JSON {@link ErrorResponse} — the servlet container would throw
     * {@code HttpMessageNotWritableException}. Just log and let the stream
     * close naturally; the client detects the dropped connection.
     */
    @ExceptionHandler(java.io.IOException.class)
    public void handleSseIOException(java.io.IOException ex, jakarta.servlet.http.HttpServletResponse response) {
        if ("text/event-stream".equals(response.getContentType())) {
            log.debug("SSE stream error (client likely disconnected): {}", ex.getMessage());
            return; // response is already committed; nothing to write
        }
        log.error("Unexpected I/O error", ex);
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
