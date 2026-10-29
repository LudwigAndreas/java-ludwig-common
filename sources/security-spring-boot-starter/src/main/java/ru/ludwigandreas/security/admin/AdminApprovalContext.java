package ru.ludwigandreas.security.admin;

import java.util.Optional;

/**
 * Who requested and who approved the administrative call this thread is executing, while an approved request runs.
 *
 * <p>Read by {@link AdminAudit}, so the executed operation's own audit event names both people without its handler
 * knowing approval exists.
 *
 * @param requestedBy the requester's subject
 * @param approvedBy  the approver's subject
 */
record AdminApprovalContext(String requestedBy, String approvedBy) {

    private static final ThreadLocal<AdminApprovalContext> CURRENT = new ThreadLocal<>();

    static Optional<AdminApprovalContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void bind(AdminApprovalContext context) {
        CURRENT.set(context);
    }

    static void unbind() {
        CURRENT.remove();
    }
}
