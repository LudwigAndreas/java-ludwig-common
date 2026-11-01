package ru.ludwigandreas.security.operation;

import java.util.Optional;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Proof that a call was authorized: the operation, the caller, and the reason it stated. Only {@link
 * OperationEvaluator} creates one, which is what lets a surface bind it to the call - an administrative invocation,
 * say - without being able to bind anything that was not authorized.
 */
public final class Authorization {

    private final Operation operation;
    private final LudwigPrincipal principal;
    private final String reason;

    Authorization(Operation operation, LudwigPrincipal principal, String reason) {
        this.operation = operation;
        this.principal = principal;
        this.reason = reason;
    }

    /**
     * The operation the call performs.
     *
     * @return it
     */
    public Operation operation() {
        return operation;
    }

    /**
     * The authorized caller.
     *
     * @return it
     */
    public LudwigPrincipal principal() {
        return principal;
    }

    /**
     * The reason the caller stated, trimmed.
     *
     * @return it, when stated
     */
    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }
}
