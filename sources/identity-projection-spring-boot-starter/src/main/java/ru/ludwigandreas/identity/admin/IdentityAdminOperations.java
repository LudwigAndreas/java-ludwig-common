package ru.ludwigandreas.identity.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code identity} administrative area: the partner registry, per-subject data-scope grants, and what a subject is
 * entitled to. Every change is privileged, because each changes who may access what.
 */
public final class IdentityAdminOperations implements OperationContributor {

    /** See the registered partners. */
    public static final String PARTNER_READ = "identity.partner.read";

    /** Register a new partner. */
    public static final String PARTNER_ONBOARD = "identity.partner.onboard";

    /** Replace a partner's name, certificates and roles. */
    public static final String PARTNER_REPLACE = "identity.partner.replace";

    /** Suspend a partner, refusing its authentication. */
    public static final String PARTNER_SUSPEND = "identity.partner.suspend";

    /** Reactivate a suspended partner. */
    public static final String PARTNER_ACTIVATE = "identity.partner.activate";

    /** See the per-subject data-scope grants. */
    public static final String GRANT_READ = "identity.grant.read";

    /** Grant a subject a data scope. */
    public static final String GRANT_CREATE = "identity.grant.create";

    /** Revoke a subject's data-scope grant, keeping its history. */
    public static final String GRANT_REVOKE = "identity.grant.revoke";

    /** See a subject's effective roles, attributes and grants. */
    public static final String AUTHORITY_READ = "identity.authority.read";

    /** See the identity sources, their feeds, user counts and sync state. */
    public static final String SOURCE_READ = "identity.source.read";

    private static final String KEY = "ludwig.identity.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(PARTNER_READ, Sensitivity.READ, KEY + PARTNER_READ),
                Operation.admin(PARTNER_ONBOARD, Sensitivity.PRIVILEGED, KEY + PARTNER_ONBOARD),
                Operation.admin(PARTNER_REPLACE, Sensitivity.PRIVILEGED, KEY + PARTNER_REPLACE),
                Operation.admin(PARTNER_SUSPEND, Sensitivity.PRIVILEGED, KEY + PARTNER_SUSPEND),
                Operation.admin(PARTNER_ACTIVATE, Sensitivity.PRIVILEGED, KEY + PARTNER_ACTIVATE),
                Operation.admin(GRANT_READ, Sensitivity.READ, KEY + GRANT_READ),
                Operation.admin(GRANT_CREATE, Sensitivity.PRIVILEGED, KEY + GRANT_CREATE),
                Operation.admin(GRANT_REVOKE, Sensitivity.PRIVILEGED, KEY + GRANT_REVOKE),
                Operation.admin(AUTHORITY_READ, Sensitivity.READ, KEY + AUTHORITY_READ),
                Operation.admin(SOURCE_READ, Sensitivity.READ, KEY + SOURCE_READ));
    }
}
