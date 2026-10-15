package ru.ludwigandreas.grpc.server.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.autoconfigure.properties.PropertyMapping;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * A test slice for a gRPC service: this starter's auto-configurations and nothing else, served on
 * the in-process transport.
 *
 * <p>No socket, no TLS and no port to collide on, but the same interceptor chain a deployed server
 * runs - authentication, problem mapping and the context bridge included - because a service test
 * that bypassed the chain would pass for a handler that only works without it.
 *
 * <p>Inject the {@code ManagedChannel} the slice provides and build a stub on it:
 *
 * <pre>{@code
 * @LudwigGrpcServerTest
 * class CatalogGrpcServiceTest {
 *     @Autowired ManagedChannel channel;
 *
 *     @Test
 *     void findsAProduct() {
 *         var stub = CatalogServiceGrpc.newBlockingStub(channel);
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>The service under test and whatever it delegates to come from the test's own configuration.
 * Authentication is on, as in production: declare a {@code JwtDecoder} bean, or set
 * {@code ludwig.grpc.server.security.enabled=false} in {@link #properties()}. A further
 * auto-configuration the test needs - observability's, for a correlation id - is added the usual
 * way, with {@code @ImportAutoConfiguration} on the test class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@OverrideAutoConfiguration(enabled = false)
@ImportAutoConfiguration
public @interface LudwigGrpcServerTest {

    /**
     * Properties for the test, as {@code key=value}.
     *
     * @return the properties
     */
    @org.springframework.core.annotation.AliasFor(annotation = SpringBootTest.class, attribute = "properties")
    String[] properties() default {};

    /**
     * The in-process name the server is registered under. Random by default, so two test contexts
     * held in Spring's context cache at once cannot collide on it.
     *
     * @return the name
     */
    @PropertyMapping("ludwig.grpc.server.in-process-name")
    String inProcessName() default "${random.uuid}";
}
