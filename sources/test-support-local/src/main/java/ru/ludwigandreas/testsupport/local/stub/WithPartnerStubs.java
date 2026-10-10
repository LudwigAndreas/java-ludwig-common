package ru.ludwigandreas.testsupport.local.stub;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Starts a service's partner stubs for an integration test and wires its rest clients to them.
 *
 * <p>The stubs start before the application context, each client's
 * {@code ludwig.rest-client.clients.<name>.base-url} is pointed at its stub, and after every test
 * method each stub is returned to healthy answers with no fault and no recorded requests - so a fault
 * set by one test never answers another's request.
 *
 * <p>The started stubs are a bean:
 *
 * <pre>{@code
 * @SpringBootTest
 * @WithPartnerStubs(CatalogStubs.class)
 * class SupplierOutageIT {
 *
 *     @Autowired
 *     StubServers stubs;
 *
 *     @Test
 *     void degradesWhenTheDirectoryIsDown() {
 *         stubs.stub("suppliers").fail(StubFault.UNAVAILABLE);
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>Contexts are cached per catalog, so every test class naming the same catalog and otherwise the
 * same configuration shares one context and one set of servers.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(PartnerStubsResetExtension.class)
public @interface WithPartnerStubs {

    /**
     * The service's stub catalog.
     *
     * @return a catalog class with a public no-argument constructor
     */
    Class<? extends StubCatalog> value();
}
