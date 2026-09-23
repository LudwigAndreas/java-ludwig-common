package ru.ludwigandreas.restclient.registrar;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.service.annotation.HttpExchange;
import ru.ludwigandreas.restclient.error.RestClientAuthenticationException;
import ru.ludwigandreas.restclient.error.RestClientException;
import ru.ludwigandreas.restclient.error.RestClientResponseException;
import ru.ludwigandreas.restclient.spi.FallbackContext;
import ru.ludwigandreas.restclient.spi.FallbackHandler;

/**
 * Routes a failed call to its {@link FallbackHandler}.
 *
 * <h2>What is, and is not, worth falling back from</h2>
 *
 * <p>A fallback runs for a failure that means <em>the call could not be made or completed</em>: a
 * spent retry budget, an open breaker, a refused bulkhead permit, a timeout, a transport error, a
 * 5xx. It deliberately does not run for:
 *
 * <ul>
 *   <li><strong>A 4xx.</strong> The peer answered, and the answer is that this service sent something
 *       wrong. Substituting cached data for a 403 is how an authorization defect ships; substituting
 *       it for a 422 is how a validation bug becomes invisible.</li>
 *   <li><strong>An authentication failure.</strong> This service's own credentials are
 *       misconfigured. A fallback would make a broken deployment look healthy for as long as the
 *       stale data lasts.</li>
 * </ul>
 *
 * <p>Handlers are resolved lazily and cached: resolving them eagerly would make the context depend
 * on a bean that a client with a fallback for one method does not need for the others.
 */
public class FallbackInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(FallbackInvocationHandler.class);

    private final Object target;
    private final String clientName;
    private final Class<?> interfaceType;
    private final String globalHandler;
    private final Map<String, String> perMethod;
    private final Function<String, FallbackHandler> resolver;
    private final Map<String, FallbackHandler> cache = new HashMap<>();
    private final Map<Method, Exchange> exchanges = new ConcurrentHashMap<>();

    /**
     * Methods this handler has already made reflectively callable.
     *
     * <p>A client interface does not have to be {@code public} - a service may well keep one
     * package-private next to its only user - and a JDK proxy's {@code Method} handle for a
     * non-public interface is not accessible from this package without being told so. Done once per
     * method rather than per call, and tolerant of a module system that refuses.
     */
    private final Map<Method, Boolean> accessible = new ConcurrentHashMap<>();

    /** Wraps {@code target} so a call it cannot complete is answered by a fallback handler. */
    // CHECKSTYLE.OFF: ParameterNumber - the proxy target plus its fallback configuration.
    public FallbackInvocationHandler(Object target, String clientName, Class<?> interfaceType,
                                     String globalHandler, Map<String, String> perMethod,
                                     Function<String, FallbackHandler> resolver) {
        this.target = target;
        this.clientName = clientName;
        this.interfaceType = interfaceType;
        this.globalHandler = globalHandler;
        this.perMethod = Map.copyOf(perMethod);
        this.resolver = resolver;
    }
    // CHECKSTYLE.ON: ParameterNumber

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        accessible.computeIfAbsent(method, FallbackInvocationHandler::makeAccessible);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable failure = ex.getTargetException();
            FallbackHandler handler = handlerFor(method);
            if (handler == null || !recoverable(failure)) {
                throw failure;
            }
            Exchange exchange = exchanges.computeIfAbsent(method, FallbackInvocationHandler::describe);
            log.warn("Client '{}': {}.{} failed ({}); using fallback {}", clientName,
                    interfaceType.getSimpleName(), method.getName(), failure.toString(),
                    handler.getClass().getName());
            return handler.fallback(new FallbackContext(clientName, exchange.method(),
                    exchange.uriTemplate(), method, method.getReturnType(), failure));
        }
    }

    private boolean recoverable(Throwable failure) {
        if (failure instanceof RestClientAuthenticationException) {
            return false;
        }
        if (failure instanceof RestClientResponseException response) {
            return response.serverError();
        }
        return failure instanceof RestClientException;
    }

    private static boolean makeAccessible(Method method) {
        try {
            method.setAccessible(true);
            return true;
        } catch (RuntimeException ex) {
            // A module system that refuses. The call still works for a public interface, which is
            // the overwhelmingly common case, so this is not worth failing over.
            log.debug("Could not make {} reflectively accessible: {}", method, ex.toString());
            return false;
        }
    }

    /**
     * Reads the method and URI template off the interface method's own annotation.
     *
     * <p>{@code findMergedAnnotation} rather than {@code getAnnotation}: {@code @GetExchange} is
     * meta-annotated with {@code @HttpExchange}, so only the merged view sees the {@code GET} and the
     * path. Cached per method, because it is reflection on a failure path that a degraded dependency
     * makes hot.
     */
    private static Exchange describe(Method method) {
        HttpExchange exchange = AnnotatedElementUtils.findMergedAnnotation(method, HttpExchange.class);
        if (exchange == null) {
            return new Exchange("UNKNOWN", method.getName());
        }
        String httpMethod = exchange.method().isBlank() ? "UNKNOWN" : exchange.method();
        String template = exchange.value().isBlank() ? "/" : exchange.value();
        return new Exchange(httpMethod, template);
    }

    /** What one interface method says about the request it makes. */
    private record Exchange(String method, String uriTemplate) {
    }

    private synchronized FallbackHandler handlerFor(Method method) {
        String beanName = perMethod.getOrDefault(interfaceType.getSimpleName() + "#" + method.getName(),
                perMethod.getOrDefault(method.getName(), globalHandler));
        if (beanName == null) {
            return null;
        }
        return cache.computeIfAbsent(beanName, resolver);
    }
}
