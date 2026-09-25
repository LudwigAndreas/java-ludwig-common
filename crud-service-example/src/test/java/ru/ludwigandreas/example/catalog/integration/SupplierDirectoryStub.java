package ru.ludwigandreas.example.catalog.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;

/**
 * The supplier directory, as an HTTP server the real client actually calls.
 *
 * <h2>Why an HTTP stub and not a stubbed interface</h2>
 *
 * <p>Replacing {@code SupplierDirectoryApi} with a test double would exercise the enricher and nothing
 * else. What the demonstration is about is the seam: that the enrichment stage reaches the partner
 * through a declarative {@code @LudwigRestClient} with the deployment's pool, timeouts and retry, that
 * the ids leave as repeated query parameters, and that the JSON comes back into the record the report
 * writes. All of that lives between the interface and the socket, and a double removes exactly that
 * part.
 *
 * <p>It also lets the failure tests be honest. "The directory is down" is a 500 from a server, not a
 * mock configured to throw: the client's retry runs, its circuit breaker sees the failures, and the
 * export engine's degradation policy is applied to whatever finally comes out - which is the sequence
 * production will run, and not one a thrown exception can stand in for.
 */
final class SupplierDirectoryStub implements AutoCloseable {

    /** Suppliers the directory knows about. */
    static final String SUPPLIER_ACME = "acme";
    static final String SUPPLIER_GLOBEX = "globex";

    /**
     * A supplier the directory has never heard of.
     *
     * <p>Products reference it, which is the ordinary situation a {@code MissingPolicy} exists for: a
     * supplier is retired from the directory while the products it supplied stay in the catalogue.
     */
    static final String SUPPLIER_RETIRED = "initech";

    private static final String PATH = "/api/v1/suppliers";

    /**
     * The directory's healthy answer.
     *
     * <p>Two suppliers, and {@link #SUPPLIER_RETIRED} deliberately not among them.
     */
    private static final String HEALTHY_BODY = """
            [
              {"id":"acme","name":"ACME GmbH","ratingClass":"A","onTimeRate":0.985},
              {"id":"globex","name":"Globex Corp","ratingClass":"B","onTimeRate":0.812}
            ]
            """;

    private final WireMockServer server;

    private SupplierDirectoryStub(WireMockServer server) {
        this.server = server;
    }

    /**
     * Starts the directory on an ephemeral port, answering for the two suppliers it knows.
     *
     * <p>{@code initech} is deliberately absent from the answer rather than returned with a null name.
     * An id missing from the response body is how a real directory says "no such supplier", and it is
     * the input the module's not-found policy is written against.
     */
    static SupplierDirectoryStub startHealthy() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        stubHealthy(server);
        return new SupplierDirectoryStub(server);
    }

    /** Makes every lookup fail, for the degradation test. */
    void breakIt() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
    }

    /** Puts the healthy answer back, so one stub can serve a whole test class. */
    void healIt() {
        server.resetAll();
        stubHealthy(server);
    }

    private static void stubHealthy(WireMockServer server) {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(HEALTHY_BODY)));
    }

    /** Forgets the request log, so a test can count its own calls. */
    void resetRequests() {
        server.resetRequests();
    }

    /** Every lookup the directory received since the last reset. */
    List<LoggedRequest> lookups() {
        return server.findAll(getRequestedFor(urlPathEqualTo(PATH)));
    }

    String baseUrl() {
        return "http://localhost:" + server.port();
    }

    @Override
    public void close() {
        server.stop();
    }
}
