package ru.ludwigandreas.security.admin;

import java.util.Optional;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * The administrative operation the current request is performing, who is performing it, and why.
 *
 * <p>Bound by the surface guard once the caller has been authorized, for the duration of the request's handling on
 * its thread, and read by the module service that performs the operation - for its audit record and its tenant
 * predicate. Work a handler hands to another thread does not carry it; such work records its audit event before
 * handing off.
 *
 * @param operation the operation the handler declared
 * @param principal the authorized caller
 * @param reason    the {@code Admin-Reason} the caller stated, or null
 */
public record AdminInvocation(AdminOperation operation, LudwigPrincipal principal, String reason) {

    /** The header carrying the caller's reason. Mandatory for a privileged operation. */
    public static final String REASON_HEADER = "Admin-Reason";

    /** The longest reason accepted, after trimming. */
    public static final int MAX_REASON_LENGTH = 500;

    private static final ThreadLocal<AdminInvocation> CURRENT = new ThreadLocal<>();

    /**
     * The invocation of the request this thread is handling.
     *
     * @return the invocation, or empty outside an authorized administrative request
     */
    public static Optional<AdminInvocation> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The invocation of the request this thread is handling, which the caller requires to exist.
     *
     * @return the invocation
     * @throws IllegalStateException outside an authorized administrative request
     */
    public static AdminInvocation require() {
        return current().orElseThrow(() -> new IllegalStateException("No administrative operation is being"
                + " performed on this thread. AdminInvocation is bound only while an @AdminAccess handler runs."));
    }

    /**
     * Binds an invocation to this thread until the returned scope is closed, which restores whatever was bound before.
     * Package-private: an invocation is bound only by {@link AdminAccessEvaluator#authorize}, after the caller has
     * been found to hold the operation.
     */
    static Scope open(AdminInvocation invocation) {
        AdminInvocation previous = CURRENT.get();
        CURRENT.set(invocation);
        return new Scope(invocation, previous);
    }

    /**
     * An administrative invocation bound to the current thread - for an MVC request, a gRPC call, or anything else
     * that authorized through {@link AdminAccessEvaluator#authorize}. Closing it restores the previous binding.
     */
    public static final class Scope implements AutoCloseable {

        private final AdminInvocation invocation;
        private final AdminInvocation previous;
        private boolean closed;

        private Scope(AdminInvocation invocation, AdminInvocation previous) {
            this.invocation = invocation;
            this.previous = previous;
        }

        /**
         * The invocation this scope bound.
         *
         * @return it
         */
        public AdminInvocation invocation() {
            return invocation;
        }

        /**
         * Binds the same invocation again, for a transport that runs one call across several callbacks and threads:
         * gRPC re-binds it around each callback. Only an authorized scope can be reopened.
         *
         * @return the new binding; close it when the callback returns
         */
        public Scope reopen() {
            return open(invocation);
        }

        /** Restores the previous binding; a second close does nothing. */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * The stated reason.
     *
     * @return the reason, or empty when none was given
     */
    public Optional<String> statedReason() {
        return Optional.ofNullable(reason);
    }

    /**
     * Which tenant's resources this caller may reach.
     *
     * @return unconfined for a service principal, confined to the caller's own tenant otherwise
     */
    public TenantScope tenantScope() {
        return principal.type() == PrincipalType.SERVICE
                ? TenantScope.unconfined()
                : TenantScope.confinedTo(principal.tenant().orElse(null));
    }
}
