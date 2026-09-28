package ru.ludwigandreas.idempotency.web;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import ru.ludwigandreas.idempotency.api.Idempotent;
import ru.ludwigandreas.idempotency.api.IdempotencyScopes;
import ru.ludwigandreas.idempotency.config.IdempotencyProperties;

/**
 * Decides which requests the filter claims, and under which scope.
 *
 * <h2>Two ways in, and why both exist</h2>
 *
 * <p>A path-and-method matcher in configuration, and the {@link Idempotent} annotation on a handler. The
 * annotation is the better one where it is available: a service that owns its controllers says so in the
 * code that would break if the path changed, whereas an Ant pattern in a YAML file silently stops
 * matching when somebody renames a route - and the failure is that dedup quietly switches off. The
 * configuration form exists for the endpoints a service does not own, which is every endpoint a starter
 * contributed.
 *
 * <p>They compose by union. The annotation can only <em>widen</em> what configuration selected: an
 * endpoint the deployment decided needs dedup is not something a class annotation should be able to opt
 * out of, because the deployment is the party that knows whether this service runs at more than one
 * replica.
 *
 * <h2>Why resolving the handler here is not free, and why it is worth it</h2>
 *
 * <p>Reading the annotation means asking Spring's {@link RequestMappingHandlerMapping} which handler a
 * request would reach, which is the same lookup the dispatcher is about to do. It is cached by the
 * mapping itself and is cheap relative to a database claim, but it is not nothing - so it happens only
 * for a method in the configured set, and never for a {@code GET}.
 *
 * <p>The mapped pattern, not the resolved URI, is what a per-endpoint scope is built from. A resolved
 * URI would put a path variable in the scope and give every resource its own scope, which is a scope per
 * row: the table would grow a namespace per id and two retries against the same resource would still
 * dedup, but the scope column would be unreadable and unbounded.
 */
@Slf4j
public class IdempotencyEndpointMatcher {

    private final IdempotencyProperties properties;

    /**
     * Resolves Spring MVC's mapping, lazily.
     *
     * <p>A supplier rather than the mapping itself, and that is load-bearing rather than fussy. This matcher
     * is constructed as part of a servlet {@code Filter}, which Boot creates before
     * {@code WebMvcAutoConfiguration} has produced a {@link RequestMappingHandlerMapping}: resolving it in
     * the constructor gets null, and the matcher then silently never sees an {@code @Idempotent} annotation.
     * With an empty {@code path-patterns} list - the documented, recommended configuration - that means the
     * filter matches nothing at all and dedup is quietly off. Resolving on first use instead is the whole
     * difference between a working feature and a feature that has no failure.
     */
    private final Supplier<RequestMappingHandlerMapping> handlerMappings;

    private final AntPathMatcher paths = new AntPathMatcher();

    /** Memoised once the mapping exists, because it does not come and go afterwards. */
    private volatile RequestMappingHandlerMapping resolvedMapping;

    /**
     * Cache of the annotation lookup, by handler method.
     *
     * <p>Bounded by the number of handler methods in the application, which is fixed at startup. The
     * lookup itself walks the method and then its declaring class, which is not expensive but is pure
     * reflection on a per-request path.
     */
    private final ConcurrentMap<Method, Optional<Idempotent>> annotations = new ConcurrentHashMap<>();

    /**
     * Creates the matcher.
     *
     * @param properties      the configuration
     * @param handlerMappings resolves Spring MVC's mapping on first use, answering null in a context with no
     *                        MVC - in which case only the configured patterns can match, which is correct
     *                        rather than degraded
     */
    public IdempotencyEndpointMatcher(IdempotencyProperties properties,
                                      Supplier<RequestMappingHandlerMapping> handlerMappings) {
        this.properties = properties;
        this.handlerMappings = handlerMappings;
    }

    /** Spring MVC's mapping, once it exists. */
    private Optional<RequestMappingHandlerMapping> handlerMapping() {
        RequestMappingHandlerMapping cached = resolvedMapping;
        if (cached == null) {
            cached = handlerMappings.get();
            resolvedMapping = cached;
        }
        return Optional.ofNullable(cached);
    }

    /** Whether this request is claimed at all. */
    public boolean matches(HttpServletRequest request) {
        if (!properties.getHttp().isEnabled() || !methodMatches(request)) {
            return false;
        }
        return pathMatches(request) || annotationOf(request).isPresent();
    }

    /**
     * The scope claims from this request are made in.
     *
     * <p>An explicit {@code scope} on the annotation wins, which is for the case where two handlers are
     * two spellings of one operation - a deprecated path kept alongside its replacement - and a key used
     * against either must dedup against the other.
     */
    public String scopeOf(HttpServletRequest request) {
        return annotationOf(request)
                .map(Idempotent::scope)
                .filter(scope -> !scope.isBlank())
                .orElseGet(() -> derivedScope(request));
    }

    /** Whether a request with no key is refused rather than let through. */
    public boolean keyRequired(HttpServletRequest request) {
        return properties.getHttp().isKeyRequired()
                || annotationOf(request).map(Idempotent::required).orElse(false);
    }

    private boolean methodMatches(HttpServletRequest request) {
        String method = request.getMethod() == null
                ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        return properties.getHttp().getMethods().stream()
                .anyMatch(configured -> configured.equalsIgnoreCase(method));
    }

    private boolean pathMatches(HttpServletRequest request) {
        List<String> patterns = properties.getHttp().getPathPatterns();
        if (patterns.isEmpty()) {
            return false;
        }
        String path = request.getRequestURI();
        return patterns.stream().anyMatch(pattern -> paths.match(pattern, path));
    }

    /**
     * The scope derived from the request.
     *
     * <p>Falls back to the resolved URI only when the mapping cannot say what pattern was matched, which
     * happens in a context with no MVC mapping at all. That is a worse scope, and the log line says so
     * once rather than per request - a scope per resource dedups correctly and reads badly, so it is a
     * degradation worth mentioning and not worth failing on.
     */
    private String derivedScope(HttpServletRequest request) {
        if (!properties.getHttp().isScopePerEndpoint()) {
            return IdempotencyScopes.HTTP;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = pattern == null ? request.getRequestURI() : pattern.toString();
        return IdempotencyScopes.forEndpoint(request.getMethod(), path);
    }

    /**
     * The {@link Idempotent} annotation on the handler this request would reach, if any.
     *
     * <p>The method's own annotation wins over the controller's, which is the ordinary Spring convention
     * and the one a reader expects: a class-level annotation is a default for the controller and a
     * method-level one is a statement about that operation.
     */
    private Optional<Idempotent> annotationOf(HttpServletRequest request) {
        return handlerMapping().flatMap(mapping -> handlerMethod(mapping, request))
                .flatMap(handler -> annotations.computeIfAbsent(handler.getMethod(),
                        ignored -> Optional.ofNullable(
                                Optional.ofNullable(handler.getMethodAnnotation(Idempotent.class))
                                        .orElseGet(() -> handler.getBeanType()
                                                .getAnnotation(Idempotent.class)))));
    }

    private Optional<HandlerMethod> handlerMethod(RequestMappingHandlerMapping mapping,
                                                  HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = mapping.getHandler(request);
            if (chain != null && chain.getHandler() instanceof HandlerMethod handler) {
                return Optional.of(handler);
            }
            return Optional.empty();
        } catch (Exception e) {
            // A mapping lookup that throws - an ambiguous mapping, a 405 raised eagerly - is the
            // dispatcher's problem to report, not this filter's. Treated as "no annotation" so the
            // request proceeds and fails in the place that knows how to describe the failure.
            log.debug("Could not resolve a handler for {} {}; treating it as unannotated",
                    request.getMethod(), request.getRequestURI(), e);
            return Optional.empty();
        }
    }
}
