package ru.ludwigandreas.grpc.core.context;

import io.grpc.Context;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Where a call's ambient facts live while gRPC moves it between threads.
 *
 * <p>The platform keeps the caller's identity, the correlation id and the caller's preferences in
 * thread-locals, and that works for a servlet request because one thread serves it from start to
 * finish. A gRPC call has no such thread: its events are delivered by whichever executor thread is
 * free, and the thread that ran the interceptors is frequently not the one that runs the handler.
 * gRPC's own answer is {@link Context}, which it propagates across those hops, so the facts are
 * stored here and restored into the thread-locals around every callback by
 * {@link ContextRestoringServerInterceptor}.
 *
 * <p>The authentication is held as {@code Object} deliberately. This module does not depend on
 * Spring Security - a client-only service may not have it - and the server starter, which does,
 * is the only code that reads the key.
 */
public final class GrpcCallContext {

    /**
     * The correlation id of the call.
     */
    public static final Context.Key<String> CORRELATION_ID = Context.key("ludwig-correlation-id");

    /**
     * The authenticated caller; a Spring Security {@code Authentication} when the server starter
     * set it.
     */
    public static final Context.Key<Object> AUTHENTICATION = Context.key("ludwig-authentication");

    /**
     * The bearer token the caller authenticated with, for relaying it to a downstream service.
     *
     * <p>Kept beside the authentication because the platform's own principal type deliberately
     * keeps no token, so a relay that read only the security context would find none. It lives
     * only as long as the call, and nothing writes it anywhere: the client starter reads it only
     * for a client configured to relay.
     */
    public static final Context.Key<String> INBOUND_BEARER_TOKEN = Context.key("ludwig-inbound-bearer-token");

    /**
     * The locale the caller asked for, before the platform's supported-locale negotiation.
     */
    public static final Context.Key<Locale> REQUESTED_LOCALE = Context.key("ludwig-requested-locale");

    /**
     * The time zone the caller asked for.
     */
    public static final Context.Key<ZoneId> REQUESTED_ZONE = Context.key("ludwig-requested-zone");

    /**
     * The idempotency key the caller sent.
     */
    public static final Context.Key<String> IDEMPOTENCY_KEY = Context.key("ludwig-idempotency-key");

    private GrpcCallContext() {
    }
}
