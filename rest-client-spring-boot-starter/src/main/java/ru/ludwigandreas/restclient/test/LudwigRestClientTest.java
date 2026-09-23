package ru.ludwigandreas.restclient.test;

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
 * A test slice that boots this starter and nothing else.
 *
 * <p>It exists so a service can test its own {@code @LudwigRestClient} interfaces against a stub
 * server - MockWebServer, WireMock, a {@code @RestController} on a random port - without starting the
 * application. The full context of a service that talks to six dependencies takes seconds and needs
 * a database; a test of one client's retry behaviour should need neither.
 *
 * <pre>{@code
 * @LudwigRestClientTest
 * @TestPropertySource(properties = {
 *     "ludwig.rest-client.clients.billing.base-url=http://localhost:${wiremock.port}",
 *     "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=2"
 * })
 * class BillingApiTest {
 *
 *     @Autowired BillingApi billing;
 *     ...
 * }
 * }</pre>
 *
 * <p>Interfaces are discovered the same way they are in production: from the application's
 * auto-configuration packages, i.e. below the test's {@code @SpringBootApplication}. That annotation
 * - or anything else carrying {@code @EnableAutoConfiguration} - has to be findable from the test,
 * because it is what registers those packages; a bare {@code @SpringBootConfiguration} does not, and
 * the scan would find nothing. Every service has one. A test whose interfaces live somewhere else
 * entirely adds {@code @EnableLudwigRestClients} to its own configuration class.
 *
 * <p>{@link OverrideAutoConfiguration} switches off the application's own auto-configuration and
 * {@link ImportAutoConfiguration} adds back exactly the entries listed in this annotation's
 * {@code .imports} file - the mechanism Spring Boot's own slices use. That is what keeps the slice
 * from quietly acquiring a data source when a service adds one.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@OverrideAutoConfiguration(enabled = false)
@ImportAutoConfiguration
public @interface LudwigRestClientTest {

    /**
     * Additional auto-configuration classes the test needs, e.g. a service's own.
     *
     * <p>Mapped onto the property {@code spring.test.importautoconfiguration.include}, which is how
     * Boot's slices let a test widen what they import without giving up the slice.
     */
    @PropertyMapping("spring.test.importautoconfiguration.include")
    Class<?>[] include() default {};
}
