package ru.ludwigandreas.grpc.client;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Injects a generated stub - blocking, future or async - bound to a named client.
 *
 * <pre>{@code
 * public NotificationSender(@LudwigGrpcClient("notifications")
 *                           NotificationServiceGrpc.NotificationServiceBlockingStub stub) { ... }
 * }</pre>
 *
 * <p>Works on a field, a constructor parameter or a {@code @Bean} method parameter. A name that is
 * not under {@code ludwig.grpc.client.clients} fails startup naming the injection point. On a
 * {@code io.grpc.Channel} it injects the client's channel.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
@Autowired
public @interface LudwigGrpcClient {

    /**
     * The client name.
     *
     * @return a name under {@code ludwig.grpc.client.clients}
     */
    String value();
}
