package ru.ludwigandreas.security.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Guards every request under {@code /admin/}, before the handler runs.
 *
 * <p>A {@link HandlerInterceptor}, for the reasons {@code PatCredentialGuard} is one: a servlet filter would have to
 * live in a security filter chain this module builds but a service may replace, and the guard has to hold whichever
 * chain is in place - including one that permits {@code /admin/**} anonymously. Its refusals are thrown, so they are
 * rendered by web-core's one problem pipeline with the same types every other endpoint uses.
 *
 * <p>In order, it refuses (the first four by {@link AdminCallerPolicy}, the next two by
 * {@link AdminAccessEvaluator#authorize(Authentication, LudwigPrincipal, AdminOperation, String, Object)}, both shared
 * with gRPC):
 *
 * <ol>
 *   <li>an unauthenticated caller, {@code 401};</li>
 *   <li>a partner, {@code 403} - an external organization is never an administrator of this service;</li>
 *   <li>a personal access token, {@code 403} with the credential-not-permitted type, unless the deployment opened
 *       the surface to them;</li>
 *   <li>a person ({@code USER}) whose validated token names none of {@code ludwig.admin.human-callers.required-via}
 *       as its acting party, when that list is set, {@code 403} - audited as a denial;</li>
 *   <li>a handler declaring an operation the caller does not hold, {@code 403};</li>
 *   <li>a privileged operation without a stated reason, {@code 400} - after the grant check, so that the
 *       requirement reveals nothing to a caller who could not perform the operation anyway;</li>
 *   <li>an {@link AdminObject} outside the caller's data scope, where the area and tier are scoped: {@code 403}, or
 *       on a read the area's not-found {@code 404};</li>
 *   <li>a handler declaring neither an operation nor {@link AdminAuthenticated}, {@code 403} - a forgotten
 *       annotation fails closed.</li>
 * </ol>
 *
 * <p>A call whose operation requires four-eyes approval is then held rather than executed, answered {@code 202}
 * with the approval; only the dispatch of an approved request runs its handler.</p>
 *
 * <p>Then it binds the {@link AdminInvocation} for the handler, and unbinds it when the request completes.
 */
@Slf4j
public class AdminSurfaceGuard implements HandlerInterceptor {

    /** The path pattern the guard is registered for. */
    public static final String PATH_PATTERN = "/admin/**";

    private final AdminOperationCatalog catalog;
    private final AdminAccessEvaluator evaluator;
    private final AdminCallerPolicy callers;
    private final AdminApprovals approvals;

    /**
     * Creates the guard with no acting party required of people.
     *
     * @param catalog                   the declared operations
     * @param evaluator                 the grant check
     * @param allowPersonalAccessTokens whether a PAT-credentialed caller may reach the surface
     */
    public AdminSurfaceGuard(AdminOperationCatalog catalog, AdminAccessEvaluator evaluator,
                             boolean allowPersonalAccessTokens) {
        this(catalog, evaluator, allowPersonalAccessTokens, Set.of());
    }

    /**
     * Creates the guard.
     *
     * @param catalog                   the declared operations
     * @param evaluator                 the grant check
     * @param allowPersonalAccessTokens whether a PAT-credentialed caller may reach the surface
     * @param requiredVia               acting parties a person must come through; empty requires none
     */
    public AdminSurfaceGuard(AdminOperationCatalog catalog, AdminAccessEvaluator evaluator,
                             boolean allowPersonalAccessTokens, Set<String> requiredVia) {
        this(catalog, evaluator, allowPersonalAccessTokens, requiredVia, null);
    }

    /**
     * Creates the guard with four-eyes approval.
     *
     * @param catalog                   the declared operations
     * @param evaluator                 the grant check
     * @param allowPersonalAccessTokens whether a PAT-credentialed caller may reach the surface
     * @param requiredVia               acting parties a person must come through; empty requires none
     * @param approvals                 holds calls whose operation requires approval, or null for none
     */
    public AdminSurfaceGuard(AdminOperationCatalog catalog, AdminAccessEvaluator evaluator,
                             boolean allowPersonalAccessTokens, Set<String> requiredVia, AdminApprovals approvals) {
        this(catalog, evaluator, new AdminCallerPolicy(evaluator, allowPersonalAccessTokens, requiredVia), approvals);
    }

    /**
     * Creates the guard over a shared caller policy, the one gRPC administrative methods are admitted by too.
     *
     * @param catalog   the declared operations
     * @param evaluator the grant check
     * @param callers   who may use the surface at all
     * @param approvals holds calls whose operation requires approval, or null for none
     */
    public AdminSurfaceGuard(AdminOperationCatalog catalog, AdminAccessEvaluator evaluator, AdminCallerPolicy callers,
                             AdminApprovals approvals) {
        this.catalog = catalog;
        this.evaluator = evaluator;
        this.callers = callers;
        this.approvals = approvals;
    }

    /** The request attribute holding the bound invocation's scope until the request completes. */
    private static final String SCOPE_ATTRIBUTE = AdminSurfaceGuard.class.getName() + ".SCOPE";

    /**
     * OData options administrative listings refuse: they page by keyset in a fixed order and filter with
     * {@code $filter} only, because offset paging over large, changing tables slows down and skips rows, and a count
     * over them is the query that takes a service down.
     */
    private static final List<String> UNSUPPORTED_QUERY_OPTIONS = List.of("$orderby", "$top", "$skip", "$count");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        LudwigPrincipal principal = callers.admit(authentication, handler,
                request.getMethod() + " " + request.getRequestURI()).getPrincipal();
        if (!(handler instanceof HandlerMethod method)) {
            // No handler method (an unmapped path): the caller is admitted and gets the ordinary 404.
            return true;
        }
        refuseUnsupportedQueryOptions(request);
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
        AdminInvocation.Scope scope = evaluator.authorize(authentication, principal, operation,
                request.getHeader(AdminInvocation.REASON_HEADER), method);
        return admit(request, response, authentication, method, operation, scope);
    }

    /** Object scope and four-eyes approval, after the grant: the call proceeds with its invocation bound, or not. */
    private boolean admit(HttpServletRequest request, HttpServletResponse response, Authentication authentication,
                          HandlerMethod method, AdminOperation operation, AdminInvocation.Scope scope) {
        LudwigPrincipal principal = scope.invocation().principal();
        try {
            evaluator.authorizeObject(authentication, principal, method, operation, pathVariables(request));
            if (approvals != null && approvals.requires(operation)) {
                Object executing = request.getAttribute(ApprovedRequest.EXECUTING);
                if (executing == null) {
                    // Held, not executed: the handler never runs, and the caller gets the approval.
                    approvals.hold(request, response, operation, principal, scope.invocation().reason());
                    scope.close();
                    return false;
                }
                approvals.admitExecution((UUID) executing, operation, principal);
            }
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - whatever refused the call, nothing stays bound for the next request.
            scope.close();
            throw e;
            // CHECKSTYLE.ON: IllegalCatch
        }
        request.setAttribute(SCOPE_ATTRIBUTE, scope);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception exception) {
        if (request.getAttribute(SCOPE_ATTRIBUTE) instanceof AdminInvocation.Scope scope) {
            scope.close();
        }
        AdminApprovalContext.unbind();
    }

    private static void refuseUnsupportedQueryOptions(HttpServletRequest request) {
        for (String option : UNSUPPORTED_QUERY_OPTIONS) {
            if (request.getParameter(option) != null) {
                throw InvalidAdminRequestException.queryOption(option);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

}
