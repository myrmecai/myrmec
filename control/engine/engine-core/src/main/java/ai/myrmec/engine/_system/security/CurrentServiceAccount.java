package ai.myrmec.engine._system.security;

import java.lang.annotation.*;

/**
 * Injects the authenticated {@link ServiceAccountPrincipal} for an External-API
 * ({@code /api/v1/external/**}) request into a controller method parameter.
 *
 * <p>Usage:
 * <pre>
 * &#64;PostMapping("/assistants/{id}/sessions")
 * public ResponseEntity&lt;?&gt; start(&#64;CurrentServiceAccount ServiceAccountPrincipal sa) {
 *     // sa.getServiceAccountId(), sa.getProjectId(), ...
 * }
 * </pre>
 * The parameter resolves only when the request was authenticated by the
 * external resource-server chain; otherwise it resolves to {@code null}.</p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentServiceAccount {
}
