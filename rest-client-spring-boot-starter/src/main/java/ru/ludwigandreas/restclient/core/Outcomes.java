package ru.ludwigandreas.restclient.core;

import ru.ludwigandreas.restclient.error.RestClientAuthenticationException;
import ru.ludwigandreas.restclient.error.RestClientCallNotPermittedException;
import ru.ludwigandreas.restclient.error.RestClientConnectionException;
import ru.ludwigandreas.restclient.error.RestClientTimeoutException;

/**
 * The outcome labels used in audit records.
 *
 * <p>A small fixed vocabulary rather than an exception class name, because an audit record is read
 * years later by people who do not have this codebase open, and because the label has to mean the
 * same thing when the exception type changes. The distinctions it draws are the ones somebody
 * reading an audit trail actually asks about: did we reach them, did they refuse us, did we refuse
 * ourselves, or was it our credentials.
 */
public final class Outcomes {

    /** A 2xx or 3xx answer. */
    public static final String SUCCESS = "SUCCESS";
    /** A 4xx: they answered, and rejected what we sent. */
    public static final String CLIENT_ERROR = "CLIENT_ERROR";
    /** A 5xx: they answered, and failed. */
    public static final String SERVER_ERROR = "SERVER_ERROR";
    /** A deadline passed. The request may or may not have been processed. */
    public static final String TIMEOUT = "TIMEOUT";
    /** We never reached them. */
    public static final String CONNECTION_ERROR = "CONNECTION_ERROR";
    /** We could not obtain or use a credential. Our problem, not theirs. */
    public static final String AUTH_ERROR = "AUTH_ERROR";
    /** Our own resilience policy refused to make the call. */
    public static final String NOT_PERMITTED = "NOT_PERMITTED";
    /** Anything else. */
    public static final String UNKNOWN = "UNKNOWN";

    private Outcomes() {
    }

    /** The label for a finished call. */
    public static String of(int statusCode, Throwable failure) {
        if (failure != null) {
            return ofFailure(failure);
        }
        // CHECKSTYLE.OFF: MagicNumber - the status ranges are the definition, not a tunable.
        if (statusCode >= 500) {
            return SERVER_ERROR;
        }
        if (statusCode >= 400) {
            return CLIENT_ERROR;
        }
        return statusCode > 0 ? SUCCESS : UNKNOWN;
        // CHECKSTYLE.ON: MagicNumber
    }

    private static String ofFailure(Throwable failure) {
        if (failure instanceof RestClientAuthenticationException) {
            return AUTH_ERROR;
        }
        if (failure instanceof RestClientCallNotPermittedException) {
            return NOT_PERMITTED;
        }
        if (failure instanceof RestClientTimeoutException) {
            return TIMEOUT;
        }
        return failure instanceof RestClientConnectionException ? CONNECTION_ERROR : UNKNOWN;
    }
}
