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

    static void bind(AdminInvocation invocation) {
        CURRENT.set(invocation);
    }

    static void unbind() {
        CURRENT.remove();
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
