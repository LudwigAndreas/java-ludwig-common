package ru.ludwigandreas.grpc.server;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/**
 * Marks a gRPC service implementation the platform's server serves.
 *
 * <p>Put it on a class that extends a generated {@code XxxImplBase}. The class is registered as a
 * bean, bound to the server behind the fixed interceptor chain, and reported by
 * {@code grpc.health.v1.Health} under its service name.
 *
 * <h2>What belongs in the class, and what must not</h2>
 *
 * <p>A gRPC service is an <b>adapter</b>: convert the request message, call an application service,
 * convert the answer. Authorization and transactions belong to that application service.
 *
 * <p>This is not a style preference. gRPC dispatches to the object {@code bindService()} captured,
 * not through the Spring proxy around this bean, so {@code @PreAuthorize}, {@code @Secured},
 * {@code @Transactional}, {@code @Cacheable} and {@code @Async} on a method here compile, start and
 * are never applied. {@code architecture-rules}' {@code RuleGroup.GRPC} fails the build on any of
 * them, and on a repository dependency from a class carrying this annotation.
 *
 * <p>{@code @Idempotent} is the one annotation that <em>is</em> honoured here: the server reads it
 * reflectively when it binds the service, so it does not depend on a proxy.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface LudwigGrpcService {
}
