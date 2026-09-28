package ru.ludwigandreas.restclient.resilience;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/**
 * Classifies the exceptions a transport throws, so that "the network failed" and "the peer answered
 * badly" are never confused.
 *
 * <p>Both the retry policy and the circuit breaker consult this. They have to agree: a failure that
 * is retried but not recorded would let a dependency fail every call without ever opening the
 * breaker, and a failure recorded but never retried spends the breaker's budget on something a
 * second attempt would have fixed.
 */
public final class TransportFailures {

    private TransportFailures() {
    }

    /** Whether {@code failure} is a transport-level failure worth another attempt. */
    public static boolean isRetryable(Throwable failure) {
        return unwrap(failure, candidate ->
                candidate instanceof ConnectException
                        || candidate instanceof UnknownHostException
                        || candidate instanceof NoRouteToHostException
                        || candidate instanceof SocketTimeoutException
                        || candidate instanceof TimeoutException
                        // A reset or a "connection closed" mid-exchange is nearly always a pooled
                        // connection the peer had already discarded - the single most common
                        // transient failure in a service mesh, and the one a retry always fixes.
                        || candidate instanceof SocketException);
    }

    /**
     * Whether {@code failure} is a transport failure at all - i.e. something the dependency or the
     * network did, rather than something this service did.
     *
     * <p>A TLS handshake failure counts, and is deliberately <em>not</em> retryable: a certificate
     * the truststore does not accept will not be accepted on the second attempt either, and retrying
     * only delays the error that says so.
     */
    public static boolean isTransportFailure(Throwable failure) {
        return unwrap(failure, candidate -> candidate instanceof IOException
                || candidate instanceof TimeoutException
                || candidate instanceof SSLException);
    }

    /** Whether {@code failure} is specifically a deadline being exceeded. */
    public static boolean isTimeout(Throwable failure) {
        return unwrap(failure, candidate -> candidate instanceof SocketTimeoutException
                || candidate instanceof TimeoutException
                || candidate instanceof java.net.http.HttpTimeoutException);
    }

    /** Whether {@code failure} means the connection was never usable. */
    public static boolean isConnectFailure(Throwable failure) {
        return unwrap(failure, candidate -> candidate instanceof ConnectException
                || candidate instanceof UnknownHostException
                || candidate instanceof NoRouteToHostException
                || candidate instanceof SSLException);
    }

    /**
     * Walks the cause chain, because every transport wraps.
     *
     * <p>Spring turns an {@code IOException} into a {@code ResourceAccessException}, Reactor Netty
     * wraps into its own types, and Apache wraps into {@code HttpHostConnectException}. Testing only
     * the outermost type is why a client that "retries connection failures" quietly retries nothing.
     */
    private static boolean unwrap(Throwable failure, java.util.function.Predicate<Throwable> test) {
        Throwable candidate = failure;
        int depth = 0;
        // CHECKSTYLE.OFF: MagicNumber - a cause chain deeper than ten is a cycle or a bug; the bound
        // is there so a self-referential chain cannot spin here on a failure path.
        while (candidate != null && depth < 10) {
            if (test.test(candidate)) {
                return true;
            }
            candidate = candidate.getCause() == candidate ? null : candidate.getCause();
            depth++;
        }
        // CHECKSTYLE.ON: MagicNumber
        return false;
    }
}
