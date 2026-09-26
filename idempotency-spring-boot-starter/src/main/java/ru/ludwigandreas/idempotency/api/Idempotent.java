package ru.ludwigandreas.idempotency.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler method whose work must not be done twice for one key.
 *
 * <p>One of the two ways to opt an endpoint in; the other is a path and method matcher in
 * configuration, for a service that would rather not annotate a controller it does not own. Both
 * reach the same filter - see {@code IdempotencyProperties.Http}.
 *
 * <p>The annotation is read by a {@code HandlerInterceptor} that runs after Spring has resolved the
 * handler, and it only ever <em>widens</em> what the configured matcher already selected: it cannot
 * be used to make the filter skip an endpoint the configuration matched, because an endpoint that the
 * deployment decided needs dedup is not something a class annotation should be able to opt out of.
 *
 * <pre>{@code
 * @Idempotent(required = true)
 * @PostMapping("/orders")
 * ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrder order) { ... }
 * }</pre>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * The scope claims from this handler are made in, or empty to derive it from the method and the
     * mapped path.
     *
     * <p>Naming it explicitly is for the case where two handlers are two spellings of one operation -
     * a deprecated path kept alongside its replacement - and a key used against either must dedup
     * against the other. Deriving it is right for everything else.
     *
     * @return the scope name
     */
    String scope() default "";

    /**
     * Whether a request with no key is refused.
     *
     * <p>Off by default, because a fire-and-forget caller who genuinely accepts a duplicate on a retry
     * is a real caller, and forcing a key on them produces keys made of random values - which protect
     * nothing while making the table grow. On for an endpoint where a double execution is not
     * recoverable: a payment, a transfer, anything that moves money or sends a message to a person.
     *
     * @return whether the key is mandatory
     */
    boolean required() default false;
}
