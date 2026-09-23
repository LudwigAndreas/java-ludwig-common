package ru.ludwigandreas.restclient.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;

/**
 * Marks an interface as a declarative HTTP client bound to one named client from
 * {@code ludwig.rest-client.clients.*}.
 *
 * <p>The interface itself carries Spring's HTTP interface annotations and nothing else:
 *
 * <pre>{@code
 * @LudwigRestClient("billing")
 * public interface BillingApi {
 *     @GetExchange("/invoices/{id}")
 *     Invoice invoice(@PathVariable UUID id);
 * }
 * }</pre>
 *
 * <p>A proxy is registered as a bean, so the interface is injected like any other collaborator. It
 * is bound to the transport, timeouts, authentication, resilience policy, listeners and metrics of
 * the named client - none of which appear here, because a call site should not be able to disagree
 * with the deployment about how a dependency is reached.
 *
 * <p>Return types must match the client's {@code mode}: value types for {@code sync},
 * {@code Mono}/{@code Flux}/{@code CompletableFuture} for {@code async}. The mismatch is reported at
 * startup, naming the interface, the method and the client, rather than as a
 * {@code ClassCastException} on the first call.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LudwigRestClient {

    /** The client name, as it appears under {@code ludwig.rest-client.clients}. */
    @AliasFor("name")
    String value() default "";

    /** The client name, as it appears under {@code ludwig.rest-client.clients}. */
    @AliasFor("value")
    String name() default "";

    /**
     * Bean name for the registered proxy.
     *
     * <p>Defaults to the uncapitalized simple name of the interface, which is what makes
     * constructor injection by type work without anyone naming anything. Set it only when two
     * interfaces in different packages share a simple name.
     */
    String beanName() default "";
}
