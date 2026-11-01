package ru.ludwigandreas.restclient.admin.service;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.restclient.admin.RestClientAdminOperations;
import ru.ludwigandreas.restclient.auth.provider.ClientTokenSources;
import ru.ludwigandreas.restclient.config.AuthTypes;
import ru.ludwigandreas.security.operation.Operation;

/**
 * The {@code restclienttokenreset} control: discards a client-credentials client's cached machine token on every
 * replica, so its next call mints a fresh one - after the identity provider rotated its signing key, or the client's
 * scopes changed. An early expiry and nothing more: minting, coalescing and early renewal stay as they are.
 * Idempotent: resetting an empty cache does nothing.
 */
public class RestClientTokenResetControl implements ReplicaControl<RestClientTokenResetControl.Arguments> {

    /** The control's name. */
    public static final String NAME = "restclienttokenreset";

    private final RestClientAdminService clients;
    private final ClientTokenSources tokens;

    /**
     * The client whose token to discard.
     *
     * @param client its name
     */
    public record Arguments(@NotBlank String client) {
    }

    /**
     * Creates the control.
     *
     * @param clients the named clients
     * @param tokens  their token caches
     */
    public RestClientTokenResetControl(RestClientAdminService clients, ClientTokenSources tokens) {
        this.clients = clients;
        this.tokens = tokens;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<Arguments> argumentType() {
        return Arguments.class;
    }

    @Override
    public Operation operationFor(Arguments arguments) {
        String type = clients.configured(arguments.client()).getAuth().getType();
        if (!AuthTypes.OAUTH2_CLIENT_CREDENTIALS.equals(type)) {
            throw new IllegalArgumentException("REST client '" + arguments.client() + "' does not use "
                    + AuthTypes.OAUTH2_CLIENT_CREDENTIALS + " and caches no machine token");
        }
        return RestClientAdminOperations.TOKEN_RESET_OPERATION;
    }

    @Override
    public Set<Operation> possibleOperations() {
        return Set.of(RestClientAdminOperations.TOKEN_RESET_OPERATION);
    }

    @Override
    public Optional<Object> apply(Arguments arguments) {
        // false: this replica has not called through the client yet, so its first call mints anyway.
        return Optional.of(Map.of("client", arguments.client(), "reset", tokens.invalidate(arguments.client())));
    }
}
