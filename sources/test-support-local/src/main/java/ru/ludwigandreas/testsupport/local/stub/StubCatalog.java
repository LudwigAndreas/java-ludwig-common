package ru.ludwigandreas.testsupport.local.stub;

import java.util.List;
import java.util.Set;

/**
 * Every partner stand-in a service has, written down once.
 *
 * <p>A service has one implementation, in its test sources, with a public no-argument constructor. The
 * local application, the integration tests ({@link WithPartnerStubs}) and the build-time rule
 * ({@code LocalRunRulesTest}) all read the same one, which is what stops a stub that works in a test
 * from being missing at the breakpoint.
 */
public interface StubCatalog {

    /**
     * The service's stubs, one per rest-client name.
     *
     * @return new stub instances; two stubs naming the same client fail start-up
     */
    List<PartnerStub> stubs();

    /**
     * Rest-client names deliberately left calling their configured address.
     *
     * <p>The point is that somebody decided. A client with neither a stub nor an entry here fails the
     * build and fails the launcher; a client named here is listed as pass-through in the start-up
     * summary with the address it calls.
     *
     * @return the pass-through client names
     */
    default Set<String> passThrough() {
        return Set.of();
    }
}
