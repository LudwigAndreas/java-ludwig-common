package ru.ludwigandreas.restclient.slice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.test.LudwigRestClientTest;

/**
 * Proves the test slice this module ships actually works, from the outside.
 *
 * <p>It is the one test written the way a consuming service would write one: an annotation, a stub
 * server, an autowired interface. Everything else in the suite drives the auto-configurations
 * directly, which would keep passing even if the slice's {@code .imports} file were wrong.
 */
@LudwigRestClientTest
class LudwigRestClientTestSliceTest {

    private static MockWebServer server;

    @Autowired
    private SliceApi api;

    @Autowired
    private ApplicationContext context;

    @BeforeAll
    static void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        server.shutdown();
    }

    @DynamicPropertySource
    static void clientProperties(DynamicPropertyRegistry registry) {
        registry.add("ludwig.rest-client.clients.slice.base-url", () -> server.url("/").toString());
        registry.add("ludwig.rest-client.clients.slice.read-timeout", () -> "2s");
        registry.add("ludwig.rest-client.clients.slice.resilience.retry.max-attempts", () -> 2);
        registry.add("ludwig.rest-client.clients.slice.resilience.retry.wait-duration", () -> "10ms");
    }

    @Test
    @DisplayName("the slice discovers the interface and calls through the full pipeline")
    void callsThroughTheSlice() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("thing-7"));

        assertThat(api.thing("7")).isEqualTo("thing-7");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the slice boots this starter and not the rest of an application")
    void bootsOnlyThisStarter() {
        assertThat(context.getBean(RestClientRegistry.class).names()).containsExactly("slice");
        assertThat(context.containsBean("dataSource")).isFalse();
    }
}
