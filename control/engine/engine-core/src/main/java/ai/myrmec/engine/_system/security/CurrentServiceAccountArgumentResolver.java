package ai.myrmec.engine._system.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentServiceAccount}-annotated parameters with the
 * authenticated {@link ServiceAccountPrincipal} from the security context.
 *
 * <p>Returns {@code null} when the request was not authenticated as a service
 * account (e.g. a user/agent principal), so controllers can fail closed.</p>
 */
@Component
public class CurrentServiceAccountArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentServiceAccount.class)
                && parameter.getParameterType().equals(ServiceAccountPrincipal.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return (principal instanceof ServiceAccountPrincipal sa) ? sa : null;
    }
}
