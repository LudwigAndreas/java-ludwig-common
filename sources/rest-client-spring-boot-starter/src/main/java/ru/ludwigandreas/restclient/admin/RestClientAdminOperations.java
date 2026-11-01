package ru.ludwigandreas.restclient.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code restclient} administrative area: the named outbound clients, their machine tokens and their circuit
 * breakers, on every replica.
 */
public final class RestClientAdminOperations implements OperationContributor {

    /** See the named clients, never a credential. */
    public static final String CLIENT_READ = "restclient.client.read";

    /** See every replica's circuit breakers. */
    public static final String BREAKER_READ = "restclient.breaker.read";

    /** Discard a client's cached machine token on every replica. */
    public static final String TOKEN_RESET = "restclient.token.reset";

    /** Set a client's circuit breaker on every replica: it decides whether this service calls a partner at all. */
    public static final String BREAKER_SET = "restclient.breaker.set";

    private static final String KEY = "ludwig.restclient.admin.";

    /** {@link #BREAKER_READ}, which the breakers control declares. */
    public static final Operation BREAKER_READ_OPERATION =
            Operation.admin(BREAKER_READ, Sensitivity.READ, KEY + BREAKER_READ);

    /** {@link #TOKEN_RESET}, which the token reset control declares. */
    public static final Operation TOKEN_RESET_OPERATION =
            Operation.admin(TOKEN_RESET, Sensitivity.CHANGE, KEY + TOKEN_RESET);

    /** {@link #BREAKER_SET}, which the breaker control declares. */
    public static final Operation BREAKER_SET_OPERATION =
            Operation.admin(BREAKER_SET, Sensitivity.PRIVILEGED, KEY + BREAKER_SET);

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(CLIENT_READ, Sensitivity.READ, KEY + CLIENT_READ),
                BREAKER_READ_OPERATION, TOKEN_RESET_OPERATION, BREAKER_SET_OPERATION);
    }
}
