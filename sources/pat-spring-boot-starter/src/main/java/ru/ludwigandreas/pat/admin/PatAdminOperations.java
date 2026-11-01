package ru.ludwigandreas.pat.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code pat} administrative area: any owner's personal access tokens. Issuing and revoking are privileged:
 * each mints or removes a credential for another identity.
 */
public final class PatAdminOperations implements OperationContributor {

    /** See any owner's tokens: metadata only, never a secret or its hash. */
    public static final String TOKEN_READ = "pat.token.read";

    /** Issue a token for another owner. */
    public static final String TOKEN_ISSUE = "pat.token.issue";

    /** Revoke any owner's token. */
    public static final String TOKEN_REVOKE = "pat.token.revoke";

    private static final String KEY = "ludwig.pat.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(TOKEN_READ, Sensitivity.READ, KEY + TOKEN_READ),
                Operation.admin(TOKEN_ISSUE, Sensitivity.PRIVILEGED, KEY + TOKEN_ISSUE),
                Operation.admin(TOKEN_REVOKE, Sensitivity.PRIVILEGED, KEY + TOKEN_REVOKE));
    }
}
