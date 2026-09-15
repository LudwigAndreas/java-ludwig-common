package ru.ludwigandreas.security.audit;

import java.lang.reflect.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.authorization.method.MethodInvocationResult;
import org.springframework.security.core.Authentication;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Records {@code @PreAuthorize}/{@code @PostAuthorize} denials in the same audit trail and the same
 * counters as data-scope denials.
 *
 * <p>Without it the trail has a hole exactly where investigations start. Row-level refusals were
 * audited by {@code DataAccessGuard} from the beginning, but a caller rejected for lacking a role never
 * reached any of it: Spring Security throws inside the method interceptor, the module's
 * {@code AccessDeniedHandler} renders a 403, and nothing records who was refused what. "Why can't this
 * user do X?" then has no answer, and neither does "has anyone been probing this endpoint?".
 *
 * <p>The listener is deliberately total. An exception escaping an event listener would propagate into
 * the authorization decision that published it, so a bug in audit code could turn a clean 403 into a
 * 500 - or, worse, interfere with the denial itself. Everything here is wrapped.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthorizationDeniedAuditListener implements ApplicationListener<AuthorizationDeniedEvent<?>> {

    private static final String UNKNOWN = "unknown";

    private final AccessAuditLogger auditLogger;
    private final SecurityMetrics metrics;

    @Override
    public void onApplicationEvent(AuthorizationDeniedEvent<?> event) {
        try {
            Method method = methodOf(event.getObject());
            String resourceType = method == null ? UNKNOWN : method.getDeclaringClass().getSimpleName();
            String action = method == null ? UNKNOWN : method.getName();

            metrics.recordAccessDenied(resourceType, action);
            auditLogger.record(decisionFor(event, resourceType, action));
        } catch (RuntimeException e) {
            log.warn("Failed to audit an authorization denial; the denial itself stands", e);
        }
    }

    /**
     * Both method-security event shapes carry the invocation: {@code @PreAuthorize} publishes the
     * {@link MethodInvocation}, {@code @PostAuthorize} wraps it together with the returned value.
     * Anything else (a request-level decision, a custom {@code AuthorizationManager}) has no method,
     * and is recorded without one rather than dropped.
     */
    private Method methodOf(Object object) {
        if (object instanceof MethodInvocation invocation) {
            return invocation.getMethod();
        }
        if (object instanceof MethodInvocationResult result) {
            return result.getMethodInvocation().getMethod();
        }
        return null;
    }

    private AccessDecision decisionFor(AuthorizationDeniedEvent<?> event, String resourceType, String action) {
        String subject = UNKNOWN;
        PrincipalType principalType = null;
        try {
            Authentication authentication = event.getAuthentication().get();
            if (authentication != null && authentication.getPrincipal() instanceof LudwigPrincipal principal) {
                subject = principal.subject();
                principalType = principal.type();
            } else if (authentication != null) {
                subject = authentication.getName();
            }
        } catch (RuntimeException e) {
            // The supplier throws when there is no authentication at all - itself a denial worth
            // recording, just without a subject.
            log.debug("Authorization denied with no resolvable authentication: {}", e.toString());
        }

        return AccessDecision.builder()
                .subject(subject)
                .principalType(principalType)
                .resourceType(resourceType)
                .action(action)
                .scopeAccess("N/A")
                .granted(false)
                .reason("insufficient-authority")
                .build();
    }
}
