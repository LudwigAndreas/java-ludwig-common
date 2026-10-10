package ru.ludwigandreas.testsupport.local.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One started partner stub: its address, its recorded requests and its fault switch.
 *
 * <h2>How a fault is realised</h2>
 *
 * <p>Besides the stub's healthy answers the server carries one catch-all mapping per fault at the
 * highest priority, each bound to a state of a scenario named {@value #FAULT_SCENARIO}. Setting a
 * fault is setting that scenario's state, and clearing it is returning the scenario to its start
 * state. That is why the same switch works over HTTP with no code of this module's on the path:
 * {@code PUT /__admin/scenarios/fault/state} is WireMock's own admin call.
 *
 * <p>{@link StubFault#REFUSED} is the exception. No response can express "nothing is listening", so
 * the listener is stopped, and restarted on the same port when the fault is cleared.
 */
public final class RunningStub {

    /** The scenario whose state is the active fault. */
    public static final String FAULT_SCENARIO = "fault";

    private static final String LOOPBACK = "127.0.0.1";
    private static final int FAULT_PRIORITY = 1;
    private static final int UNAVAILABLE_STATUS = 503;

    private final PartnerStub definition;
    private final WireMockServer server;
    private final int port;
    private final Duration slowDelay;
    private final List<UUID> scopedFaults = new ArrayList<>();

    RunningStub(PartnerStub definition, int port, Duration slowDelay) {
        this.definition = definition;
        this.port = port;
        this.slowDelay = slowDelay;
        // Loopback only: a debugging session must never put a stub, or the admin API that rewrites
        // it, on the network. The files root is per client and on the classpath, so one stub never
        // picks up another's mapping files and nothing depends on the working directory.
        this.server = new WireMockServer(WireMockConfiguration.options()
                .bindAddress(LOOPBACK)
                .port(port)
                .usingFilesUnderClasspath("ludwig-local-stubs/" + definition.name()));
    }

    void start() {
        server.start();
        registerMappings();
    }

    void stop() {
        if (server.isRunning()) {
            server.stop();
        }
    }

    /**
     * The stub's name, which for a rest-client stub is the client's name.
     *
     * @return the name
     */
    public String name() {
        return definition.name();
    }

    /**
     * The stub definition this server was started from.
     *
     * @return the definition
     */
    public PartnerStub definition() {
        return definition;
    }

    /**
     * The port the stub listens on. It does not change when the stub is refused and healed.
     *
     * @return the port
     */
    public int port() {
        return port;
    }

    /**
     * The address the client is pointed at.
     *
     * @return {@code http://127.0.0.1:<port>}
     */
    public String baseUrl() {
        return "http://" + LOOPBACK + ":" + port;
    }

    /**
     * WireMock's admin API for this stub, which is the control surface while a service is paused.
     *
     * @return {@code <baseUrl>/__admin}
     */
    public String adminUrl() {
        return baseUrl() + "/__admin";
    }

    /**
     * The running server, for verifying recorded requests or registering a one-off answer.
     *
     * @return the server
     */
    public WireMockServer server() {
        return server;
    }

    /**
     * Makes every request to this stub fail.
     *
     * @param fault the fault to apply
     */
    public void fail(StubFault fault) {
        if (fault == StubFault.REFUSED) {
            stop();
            return;
        }
        ensureListening();
        server.setScenarioState(FAULT_SCENARIO, fault.state());
    }

    /**
     * Makes requests to one part of this stub fail, leaving the rest healthy.
     *
     * @param fault          the fault to apply
     * @param urlPathPattern a regular expression matched against the request path
     * @throws IllegalArgumentException for {@link StubFault#REFUSED}, which closes the whole listener
     */
    public void fail(StubFault fault, String urlPathPattern) {
        if (fault == StubFault.REFUSED) {
            throw new IllegalArgumentException("A refused connection closes the stub's listener, so it "
                    + "cannot be scoped to a path. Use fail(StubFault.REFUSED) for the whole stub.");
        }
        ensureListening();
        MappingBuilder mapping = any(urlPathMatching(urlPathPattern))
                .atPriority(FAULT_PRIORITY)
                .willReturn(response(fault));
        scopedFaults.add(server.stubFor(mapping).getId());
    }

    /** Clears every fault on this stub, whole-stub and path-scoped alike. */
    public void heal() {
        ensureListening();
        scopedFaults.forEach(server::removeStubMapping);
        scopedFaults.clear();
        server.resetScenario(FAULT_SCENARIO);
    }

    /**
     * The whole-stub fault in force, wherever it was set from.
     *
     * @return the fault, or empty when the stub answers healthily
     */
    public Optional<StubFault> activeFault() {
        if (!server.isRunning()) {
            return Optional.of(StubFault.REFUSED);
        }
        return server.getAllScenarios().getScenarios().stream()
                .filter(scenario -> FAULT_SCENARIO.equals(scenario.getName()))
                .map(Scenario::getState)
                .findFirst()
                .flatMap(StubFault::fromState);
    }

    /**
     * Returns the stub to what it was when it started: healthy answers, no fault, no recorded requests.
     *
     * <p>Deliberately not WireMock's {@code resetAll()}, which would delete the fault mappings along
     * with everything else and leave a stub whose fault switch does nothing.
     */
    public void reset() {
        ensureListening();
        scopedFaults.clear();
        server.resetMappings();
        server.resetRequests();
        registerMappings();
    }

    private void ensureListening() {
        if (!server.isRunning()) {
            server.start();
        }
    }

    private void registerMappings() {
        definition.register(server);
        for (StubFault fault : StubFault.values()) {
            if (fault == StubFault.REFUSED) {
                continue;
            }
            server.stubFor(any(anyUrl())
                    .atPriority(FAULT_PRIORITY)
                    .inScenario(FAULT_SCENARIO)
                    .whenScenarioStateIs(fault.state())
                    .willReturn(response(fault)));
        }
    }

    private ResponseDefinitionBuilder response(StubFault fault) {
        return switch (fault) {
            case UNAVAILABLE -> aResponse().withStatus(UNAVAILABLE_STATUS);
            case SLOW -> aResponse().withStatus(UNAVAILABLE_STATUS)
                    .withFixedDelay(Math.toIntExact(slowDelay.toMillis()));
            case RESET -> aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER);
            case EMPTY -> aResponse().withFault(Fault.EMPTY_RESPONSE);
            case REFUSED -> throw new IllegalArgumentException("refused has no response");
        };
    }
}
