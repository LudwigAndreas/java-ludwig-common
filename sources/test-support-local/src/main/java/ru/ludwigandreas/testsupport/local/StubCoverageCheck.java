package ru.ludwigandreas.testsupport.local;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Refuses to start a local application that has a rest client with no stand-in.
 *
 * <p>This is the run-time half of the client-implies-stub rule. The build-time half
 * ({@code LocalRunRulesTest}) sees annotated interfaces; this sees what the rest-client registry
 * actually holds, which includes a client that exists only as a configured name with no interface.
 * A client with neither a stub nor a pass-through declaration would be called for real, from a
 * laptop, at whatever address the service's configuration names.
 *
 * <p>The opposite direction - a stub nothing calls - is {@link StubAddressCheck}, which does not need
 * the rest-client starter and so covers stubs for partners that are not rest clients too.
 */
final class StubCoverageCheck implements SmartInitializingSingleton {

    private final Set<String> registeredClients;
    private final Set<String> stubbedClients;
    private final Predicate<String> passThrough;

    StubCoverageCheck(Set<String> registeredClients, Set<String> stubbedClients,
                      Predicate<String> passThrough) {
        this.registeredClients = registeredClients;
        this.stubbedClients = stubbedClients;
        this.passThrough = passThrough;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The local application cannot start: "
                    + String.join(" ", problems));
        }
    }

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        for (String client : registeredClients) {
            if (!stubbedClients.contains(client) && !passThrough.test(client)) {
                problems.add("Rest client '" + client + "' has no partner stub and no pass-through "
                        + "declaration. Add a PartnerStub for it to the StubCatalog, or name it in "
                        + "passThrough().");
            }
        }
        return problems;
    }
}
