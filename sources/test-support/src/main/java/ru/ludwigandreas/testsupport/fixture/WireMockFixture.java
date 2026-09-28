package ru.ludwigandreas.testsupport.fixture;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * An HTTP stub server on a dynamic port, with its lifecycle and its between-test reset handled.
 *
 * <h2>Why in-process and not a container</h2>
 *
 * <p>WireMock is a library. There is no image, so there is nothing to pin, and starting a JVM server
 * costs milliseconds against a container's seconds. It belongs here as a fixture.
 *
 * <h2>Why a dynamic port</h2>
 *
 * <p>A fixed port makes two modules' suites unable to run at once, and makes a developer's own service
 * on that port look like a test failure. The port is therefore allocated by the OS and published to
 * the context as a property, which is why {@link #port()} is only meaningful after the server has
 * started.
 *
 * <h2>Why the reset is automatic</h2>
 *
 * <p>A stub left registered by one test answers another test's request, and the second test passes for
 * the wrong reason - or fails with a body it never configured. {@link #resetAll()} runs after every
 * test method, so a stub's scope is the method that registered it.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does not register itself as a Spring property source. Three modules point three different
 * properties at a stub server ({@code ludwig.rest-client.clients.*.base-url} among them), and guessing
 * which one a module means would be wrong more often than useful. A test wires the URL itself, from
 * {@link #baseUrl()}, in one line.
 *
 * <p>For testing a {@code @LudwigRestClient} interface against this server without booting the whole
 * application, see {@code rest-client-spring-boot-starter}'s {@code @LudwigRestClientTest} slice -
 * this fixture is the server, that annotation is the context.
 *
 * <h2>Use</h2>
 *
 * <pre>{@code
 * @RegisterExtension
 * static WireMockFixture wireMock = new WireMockFixture();
 * }</pre>
 */
public final class WireMockFixture implements BeforeAllCallback, AfterAllCallback, AfterEachCallback {

    private final WireMockServer server;

    /** Creates a server that will listen on an OS-allocated port. */
    public WireMockFixture() {
        this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    }

    /**
     * The running server, for registering stubs and verifying requests.
     *
     * @return the server
     */
    public WireMockServer server() {
        return server;
    }

    /**
     * The port the server listens on.
     *
     * @return the port, valid only once the server has started
     */
    public int port() {
        return server.port();
    }

    /**
     * The server's base URL, for a {@code base-url} property or a client builder.
     *
     * @return {@code http://localhost:<port>}, valid only once the server has started
     */
    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    /** Forgets every stub and every recorded request. Runs automatically after each test method. */
    public void resetAll() {
        server.resetAll();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!server.isRunning()) {
            server.start();
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        resetAll();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        server.stop();
    }
}
