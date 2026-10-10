package ru.ludwigandreas.testsupport.local.stub;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.MapPropertySource;

/**
 * Starts a catalog's stubs before the context refreshes and points the clients at them.
 *
 * <h2>Why an initializer</h2>
 *
 * <p>The rest-client starter binds {@code base-url} and validates it while the context starts. The
 * address therefore has to be in the environment before the first bean is created, which rules out a
 * bean and leaves the one hook that runs between "the environment is ready" and "refresh begins".
 *
 * <h2>Why first in the property sources</h2>
 *
 * <p>A service's own {@code application.yml} names the partner's real address. If that value won, a
 * laptop would call production. The stub addresses are therefore added ahead of every other source,
 * command-line arguments included, and {@code .mode=passthrough} is the explicit way to opt out.
 */
public final class StubServersInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** The name the started stubs are registered under in the context. */
    public static final String BEAN_NAME = "ludwigLocalStubServers";

    /** The name of the property source that points the clients at the stubs. */
    public static final String PROPERTY_SOURCE_NAME = "ludwigLocalStubs";

    private final StubCatalog catalog;
    private StubServers servers;

    /**
     * Creates an initializer for one service's stubs.
     *
     * @param catalog the stubs to start
     */
    public StubServersInitializer(StubCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        servers = StubServers.start(catalog, context.getEnvironment());
        StubServers started = servers;
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, started.properties()));
        context.getBeanFactory().registerSingleton(BEAN_NAME, started);
        // A registered singleton gets no destroy callback, so the context's own close event is what
        // releases the ports.
        context.addApplicationListener((ApplicationListener<ContextClosedEvent>) event -> started.close());
    }

    /**
     * Stops the stubs when the context never got far enough to close them itself.
     *
     * <p>A context that fails during refresh publishes no close event to a listener added here, so the
     * launcher calls this on a failed start.
     */
    public void close() {
        if (servers != null) {
            servers.close();
        }
    }
}
