package ru.ludwigandreas.security.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.security.web.PatCredentialNotPermittedException;

/**
 * Guards every request under {@code /admin/}, before the handler runs.
 *
 * <p>A {@link HandlerInterceptor}, for the reasons {@code PatCredentialGuard} is one: a servlet filter would have to
 * live in a security filter chain this module builds but a service may replace, and the guard has to hold whichever
 * chain is in place - including one that permits {@code /admin/**} anonymously. Its refusals are thrown, so they are
 * rendered by web-core's one problem pipeline with the same types every other endpoint uses.
 *
 * <p>In order, it refuses:
 *
 * <ol>
 *   <li>an unauthenticated caller, {@code 401};</li>
 *   <li>a partner, {@code 403} - an external organization is never an administrator of this service;</li>
 *   <li>a personal access token, {@code 403} with the credential-not-permitted type, unless the deployment opened
 *       the surface to them;</li>
 *   <li>a handler declaring an operation the caller does not hold, {@code 403};</li>
 *   <li>a privileged operation without a stated reason, {@code 400} - after the grant check, so that the
 *       requirement reveals nothing to a caller who could not perform the operation anyway;</li>
 *   <li>a handler declaring neither an operation nor {@link AdminAuthenticated}, {@code 403} - a forgotten
 *       annotation fails closed.</li>
 * </ol>
 *
 * <p>Then it binds the {@link AdminInvocation} for the handler, and unbinds it when the request completes.
 */
@Slf4j
public class AdminSurfaceGuard implements HandlerInterceptor {

    /** The path pattern the guard is registered for. */
    public static final String PATH_PATTERN = "/admin/**";

    private final AdminOperationCatalog catalog;
    private final AdminAccessEvaluator evaluator;
    private final boolean allowPersonalAccessTokens;

    /**
     * Creates the guard.
     *
     * @param catalog                   the declared operations
     * @param evaluator                 the grant check
     * @param allowPersonalAccessTokens whether a PAT-credentialed caller may reach the surface
     */
    public AdminSurfaceGuard(AdminOperationCatalog catalog, AdminAccessEvaluator evaluator,
                             boolean allowPersonalAccessTokens) {
        this.catalog = catalog;
        this.evaluator = evaluator;
        this.allowPersonalAccessTokens = allowPersonalAccessTokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof LudwigAuthentication ludwig) || !ludwig.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("The administrative surface requires authentication");
        }
        LudwigPrincipal principal = ludwig.getPrincipal();
        if (principal.type() == PrincipalType.PARTNER) {
            log.warn("Refused partner {} on the administrative surface: {} {}", principal.subject(),
                    request.getMethod(), request.getRequestURI());
            throw new AccessDeniedException("Partners may not use the administrative surface");
        }
        if (ludwig.credential().isLongLived() && !allowPersonalAccessTokens) {
            log.warn("Refused a {} credential on the administrative surface: {} {}", ludwig.credential().kind(),
                    request.getMethod(), request.getRequestURI());
            throw new PatCredentialNotPermittedException(ludwig.credential().kind().name());
        }
        if (!(handler instanceof HandlerMethod method)) {
            // No handler method (an unmapped path): the caller is admitted and gets the ordinary 404.
            return true;
        }
        AdminAccess access = method.getMethodAnnotation(AdminAccess.class);
        if (access == null) {
            if (method.hasMethodAnnotation(AdminAuthenticated.class)) {
                return true;
            }
            log.error("Refused {} {}: handler {} is under /admin/ but declares neither @AdminAccess nor"
                            + " @AdminAuthenticated",
                    request.getMethod(), request.getRequestURI(), method.getShortLogMessage());
            throw new AccessDeniedException("Administrative handler declares no operation");
        }
        AdminOperation operation = catalog.find(access.value())
                .orElseThrow(() -> new AccessDeniedException("Uncatalogued administrative operation"));
        evaluator.authorize(authentication, principal, method, operation.id());
        String reason = reasonOf(request, operation);
        AdminInvocation.bind(new AdminInvocation(operation, principal, reason));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception exception) {
        AdminInvocation.unbind();
    }

    private static String reasonOf(HttpServletRequest request, AdminOperation operation) {
        String header = request.getHeader(AdminInvocation.REASON_HEADER);
        String reason = header == null ? "" : header.trim();
        if (reason.length() > AdminInvocation.MAX_REASON_LENGTH
                || reason.isEmpty() && operation.tier() == AdminTier.PRIVILEGED) {
            throw new AdminReasonRequiredException();
        }
        return reason.isEmpty() ? null : reason;
    }
}
