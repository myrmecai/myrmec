package ai.myrmec.engine._system.security;

import java.lang.annotation.*;

/**
 * Annotation to inject the current authenticated user's ID into controller methods.
 * 
 * Usage:
 * <pre>
 * @PostMapping
 * public ResponseEntity<?> create(@CurrentUser UUID userId, @RequestBody Request request) {
 *     // userId is extracted from the JWT token
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
