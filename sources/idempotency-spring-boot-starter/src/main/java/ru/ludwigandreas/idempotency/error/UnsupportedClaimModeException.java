package ru.ludwigandreas.idempotency.error;

import ru.ludwigandreas.idempotency.api.ClaimMode;

/**
 * A store was asked for a claim mode it cannot serve.
 *
 * <p>Not a {@code LocalizedException} and not an HTTP answer: this is a wiring mistake, not a caller's
 * mistake. A Redis-backed store configured behind a consumer that needs a transactional claim is a
 * deployment that will lose work, and the honest moment to say so is the first claim rather than the
 * first crash between a claim and a commit.
 *
 * <p>Thrown rather than degraded to the other mode, because both substitutions are silent correctness
 * bugs - see {@code IdempotencyStore}.
 */
public class UnsupportedClaimModeException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param mode  the mode that was asked for
     * @param store the store that cannot serve it
     */
    public UnsupportedClaimModeException(ClaimMode mode, Class<?> store) {
        super(store.getSimpleName() + " cannot serve a " + mode + " claim. See that class's javadoc for"
                + " why, and ludwig.idempotency.backend for how to choose one that can.");
    }
}
