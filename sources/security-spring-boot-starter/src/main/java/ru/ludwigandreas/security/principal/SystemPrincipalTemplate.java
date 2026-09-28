package ru.ludwigandreas.security.principal;

import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Runs work that has no caller - a scheduled job, a Kafka listener, an {@code @Async} task - under an
 * explicit system identity.
 *
 * <pre>{@code
 * systemPrincipal.run(() -> reconciliationService.reconcile());
 * }</pre>
 *
 * <p>Two reasons this is a deliberate call rather than something the module does automatically.
 *
 * <p>First, background work genuinely needs an identity: {@code @CreatedBy} has to record something,
 * the audit trail has to attribute the change to something, and data scoping has to resolve to
 * something. Leaving the context empty means every one of those quietly falls back to a default.
 *
 * <p>Second, making it explicit keeps the blast radius visible. The system principal usually holds wide
 * scope, so the code paths that run under it should be short, few and greppable - not "whatever
 * happened to run off a request thread". The template always restores the previous context, so a job
 * running on a pooled thread cannot leave its elevated identity behind for the next request.
 */
@RequiredArgsConstructor
public class SystemPrincipalTemplate {

    private final String subject;
    private final Set<String> roles;

    public void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    public <T> T call(Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new LudwigAuthentication(systemPrincipal()));
            SecurityContextHolder.setContext(context);
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private LudwigPrincipal systemPrincipal() {
        return LudwigPrincipal.builder()
                .subject(subject)
                .type(PrincipalType.SERVICE)
                .displayName(subject)
                .roles(roles)
                .build();
    }
}
