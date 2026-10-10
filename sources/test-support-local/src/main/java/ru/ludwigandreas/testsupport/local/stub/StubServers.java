package ru.ludwigandreas.testsupport.local.stub;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * The started stubs of one application context, by stub name.
 *
 * <p>An integration test injects this to switch a fault or read recorded requests; the launcher's
 * start-up check and summary read it too. It lives exactly as long as the context it was started for,
 * because that context's clients hold these ports.
 *
 * <h2>Configuration</h2>
 *
 * <p>All under {@value #PROPERTY_PREFIX}{@code <stub name>}:
 *
 * <ul>
 *   <li>{@code .port} - a fixed port. Unset, the operating system allocates one, so two suites never
 *       collide and a bookmarkable address is something a developer opts into;</li>
 *   <li>{@code .fault} - a {@link StubFault} in force from start-up;</li>
 *   <li>{@code .slow-delay} - how long {@link StubFault#SLOW} waits, 30 seconds unless set;</li>
 *   <li>{@code .mode} - {@code stub} or {@code passthrough}. The second leaves the stub unstarted and
 *       the client calling its configured address, for one launch.</li>
 * </ul>
 */
public final class StubServers implements AutoCloseable {

    /** The prefix of every per-stub property. */
    public static final String PROPERTY_PREFIX = "ludwig.local.stubs.";

    /** The {@code mode} value that leaves a client calling its configured address. */
    public static final String PASS_THROUGH_MODE = "passthrough";

    private static final String STUB_MODE = "stub";
    private static final Duration DEFAULT_SLOW_DELAY = Duration.ofSeconds(30);

    private final Map<String, RunningStub> running = new LinkedHashMap<>();
    private final Map<String, PartnerStub> declared = new LinkedHashMap<>();
    private final Set<String> passThrough = new LinkedHashSet<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();

    private StubServers() {
    }

    /**
     * Starts every stub of a catalog that is not switched to pass-through.
     *
     * @param catalog     the service's stubs
     * @param environment where the per-stub properties are read from
     * @return the started stubs
     * @throws IllegalStateException    when two stubs name one client, or a stub cannot listen
     * @throws IllegalArgumentException when a per-stub property holds an unknown value
     */
    public static StubServers start(StubCatalog catalog, Environment environment) {
        StubServers servers = new StubServers();
        try {
            servers.declare(catalog);
            servers.startAll(environment);
        } catch (RuntimeException ex) {
            // A half-started set would hold its ports until the JVM exits, and the next attempt
            // would then fail on a port this one never released.
            servers.close();
            throw ex;
        }
        return servers;
    }

    private void declare(StubCatalog catalog) {
        for (PartnerStub stub : catalog.stubs()) {
            PartnerStub previous = declared.putIfAbsent(stub.name(), stub);
            if (previous != null) {
                throw new IllegalStateException("Two partner stubs are named '"
                        + stub.name() + "': " + previous.getClass().getName() + " and "
                        + stub.getClass().getName() + ". A partner has one stub.");
            }
        }
        passThrough.addAll(catalog.passThrough());
    }

    private void startAll(Environment environment) {
        for (PartnerStub stub : declared.values()) {
            String name = stub.name();
            if (passThroughRequested(environment, name)) {
                passThrough.add(name);
                continue;
            }
            Duration slowDelay = environment.getProperty(
                    PROPERTY_PREFIX + name + ".slow-delay", Duration.class, DEFAULT_SLOW_DELAY);
            RunningStub started = listen(stub, environment, slowDelay);
            running.put(name, started);
            stub.address().propertyFor(name).ifPresent(property -> properties.put(property, started.baseUrl()));
            properties.putAll(stub.properties(started.baseUrl()));
            String fault = environment.getProperty(PROPERTY_PREFIX + name + ".fault");
            if (fault != null && !fault.isBlank()) {
                started.fail(StubFault.parse(fault));
            }
        }
    }

    private static boolean passThroughRequested(Environment environment, String name) {
        String mode = environment.getProperty(PROPERTY_PREFIX + name + ".mode", STUB_MODE);
        if (PASS_THROUGH_MODE.equalsIgnoreCase(mode)) {
            return true;
        }
        if (STUB_MODE.equalsIgnoreCase(mode)) {
            return false;
        }
        throw new IllegalArgumentException("'" + mode + "' is not a stub mode for partner stub '" + name
                + "'. Valid values: " + STUB_MODE + ", " + PASS_THROUGH_MODE);
    }

    // CHECKSTYLE.OFF: IllegalCatch - WireMock reports a port it cannot bind as an unchecked, shaded
    // Jetty failure with no type worth naming; whatever it is, the developer needs the client and port.
    private static RunningStub listen(PartnerStub stub, Environment environment, Duration slowDelay) {
        Integer configured = environment.getProperty(
                PROPERTY_PREFIX + stub.name() + ".port", Integer.class);
        int port = configured != null ? configured : freePort();
        RunningStub candidate = new RunningStub(stub, port, slowDelay);
        try {
            candidate.start();
        } catch (RuntimeException ex) {
            candidate.stop();
            throw new IllegalStateException("The partner stub '" + stub.name()
                    + "' cannot listen on port " + port + ". Another process may hold it; change or "
                    + "remove " + PROPERTY_PREFIX + stub.name() + ".port.", ex);
        }
        return candidate;
    }
    // CHECKSTYLE.ON: IllegalCatch

    /**
     * Picks a port the operating system considers free.
     *
     * <p>The port is chosen here rather than left to the server because a refused stub has to come
     * back on the same port - the client's {@code base-url} was bound once and does not move.
     */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("No free loopback port for a partner stub", ex);
        }
    }

    /**
     * The started stub with a name.
     *
     * @param clientName the stub's name, which for a rest-client stub is the client's name
     * @return the stub
     * @throws IllegalArgumentException when no stub is running for that name, listing the ones that are
     */
    public RunningStub stub(String clientName) {
        RunningStub stub = running.get(clientName);
        if (stub == null) {
            throw new IllegalArgumentException("No partner stub is running under the name '"
                    + clientName + "'. Running: " + running.keySet());
        }
        return stub;
    }

    /**
     * Every started stub, in catalog order.
     *
     * @return the started stubs
     */
    public Collection<RunningStub> stubs() {
        return Collections.unmodifiableCollection(running.values());
    }

    /**
     * Every stub the catalog declares, started or switched to pass-through.
     *
     * @return the declared stubs by name
     */
    public Map<String, PartnerStub> declared() {
        return Collections.unmodifiableMap(declared);
    }

    /**
     * Names left calling their configured address, by the catalog or by {@code .mode}.
     *
     * @return the pass-through names
     */
    public Set<String> passThrough() {
        return Collections.unmodifiableSet(passThrough);
    }

    /**
     * The properties that point the clients at the stubs.
     *
     * @return property names and values, to be given the highest precedence
     */
    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    /** Returns every stub to healthy answers, no fault and no recorded requests. */
    public void resetAll() {
        running.values().forEach(RunningStub::reset);
    }

    /** Stops every stub. Safe to call more than once. */
    @Override
    public void close() {
        List.copyOf(running.values()).forEach(RunningStub::stop);
    }
}
