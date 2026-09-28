package ru.ludwigandreas.storage.exception;

import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The location is not one this platform can serve.
 *
 * <p>A 400 rather than a 500 because the overwhelmingly common source of one is a configured or
 * submitted value - a bucket name pasted without its scheme, a Windows path, a key with a
 * {@code ..} segment - and telling the submitter which string was rejected is both the useful
 * response and a safe one: the string is theirs.
 *
 * <p>Thrown from {@link ru.ludwigandreas.storage.api.ObjectUri}'s constructor as well as its parser,
 * so that a location assembled in code cannot bypass the checks a parsed one goes through.
 */
public class InvalidObjectUriException extends ObjectStoreException {

    private static final long serialVersionUID = 1L;

    /**
     * A rejected location.
     *
     * @param uri the string that could not be parsed, echoed back to whoever supplied it
     */
    public InvalidObjectUriException(String uri) {
        super(ProblemStatus.INVALID, StorageProblemCodes.INVALID_URI, null, uri);
        withProperty("uri", uri);
    }
}
