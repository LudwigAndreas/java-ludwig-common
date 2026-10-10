package ru.ludwigandreas.testsupport.local.stub;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.Map;

/**
 * An HTTP stand-in for one partner of a service.
 *
 * <p>Usually the partner is a rest client and the stub carries its name. A partner the service
 * reaches some other way is covered too: see {@link #address()}.
 *
 * <h2>Why a server and not a stubbed interface</h2>
 *
 * <p>Replacing the client interface with a double removes exactly the part worth exercising: the pool,
 * the timeouts, the retry, the circuit breaker and the error translation all live between the
 * interface and the socket. A stub is therefore a real server the real client calls, and "the partner
 * is down" is a 503 from that server rather than a mock configured to throw.
 *
 * <h2>What an implementation is</h2>
 *
 * <p>A plain class, not a Spring bean. The rest-client starter binds and validates
 * {@code ludwig.rest-client.clients.<name>.base-url} while the context starts, so a stub that was a
 * bean would exist too late to supply it. The module starts one loopback-only server per stub before
 * the context refreshes and publishes its address where {@link #address()} says.
 *
 * <p>The answers are written in WireMock's own API; this module adds no DSL over it. Leave priority
 * {@code 1} alone - the fault mappings use it to win over every healthy answer.
 *
 * <h2>A stub is a belief about a partner</h2>
 *
 * <p>Nothing fails when the partner changes its contract and this class does not. Declare a client
 * pass-through in the {@link StubCatalog} to call a real environment instead.
 */
public interface PartnerStub {

    /**
     * The stub's name: the key under {@code ludwig.local.stubs} and in {@code StubServers.stub(...)}.
     *
     * <p>For a stub that stands in for a rest client, which is the default, this is also the name
     * used in {@code @LudwigRestClient("...")} and under {@code ludwig.rest-client.clients}.
     *
     * @return the name
     */
    String name();

    /**
     * Where this stub's address is published.
     *
     * @return {@link StubAddress#restClient()} unless overridden
     */
    default StubAddress address() {
        return StubAddress.restClient();
    }

    /**
     * Registers the partner's healthy answers.
     *
     * <p>Called once when the server starts and again whenever the stub is reset, so it must be
     * repeatable and must not keep state between calls.
     *
     * @param server the server that stands in for the partner
     */
    void register(WireMockServer server);

    /**
     * Further properties this stub needs set, beyond its {@linkplain #address() address}.
     *
     * <p>The case this exists for is a client that fetches a token: overriding {@code base-url} alone
     * leaves the token URL pointing at the real identity provider. A stub that also answers for the
     * token endpoint returns that property here.
     *
     * @param baseUrl the address this stub listens on
     * @return property names and values, applied at the same precedence as the address
     */
    default Map<String, String> properties(String baseUrl) {
        return Map.of();
    }

    /**
     * Lines to print under this stub in the local application's start-up summary.
     *
     * <p>For whatever a developer would otherwise have to work out from the stub's address: a
     * ready-made command, a URL to open. Printed after the fault-switch lines.
     *
     * @param running this stub, started
     * @return the lines, without indentation
     */
    default List<String> summaryLines(RunningStub running) {
        return List.of();
    }
}
