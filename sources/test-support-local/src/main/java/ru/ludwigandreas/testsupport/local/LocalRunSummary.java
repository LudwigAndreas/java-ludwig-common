package ru.ludwigandreas.testsupport.local;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.RunningStub;
import ru.ludwigandreas.testsupport.local.stub.StubAddress;
import ru.ludwigandreas.testsupport.local.stub.StubFault;
import ru.ludwigandreas.testsupport.local.stub.StubServers;

/**
 * Logs, once, what the local application started and where it is.
 *
 * <p>The addresses are all allocated at start-up, so without this a developer who wants to open a
 * database client or make a partner fail has to go looking for ports. Each stub's line carries the
 * request that switches its fault, ready to paste, because the moment it is wanted is the moment the
 * service is paused at a breakpoint and no code can be run.
 */
final class LocalRunSummary implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LocalRunSummary.class);

    private static final String INDENT = "    ";
    private static final String NONE = "(none)";

    private final LocalRunPlan plan;
    private final ObjectProvider<StubServers> stubs;
    private final ObjectProvider<SummaryContributor> contributors;
    private final LocalFixtureRunner fixtures;
    private final Environment environment;

    LocalRunSummary(LocalRunPlan plan, ObjectProvider<StubServers> stubs,
                    ObjectProvider<SummaryContributor> contributors, LocalFixtureRunner fixtures,
                    Environment environment) {
        this.plan = plan;
        this.stubs = stubs;
        this.contributors = contributors;
        this.fixtures = fixtures;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("{}", String.join(System.lineSeparator(), lines()));
    }

    List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("Local run is ready.");
        lines.add("  Backing services");
        if (plan.services().isEmpty()) {
            lines.add(INDENT + NONE);
        }
        plan.services().stream().sorted().forEach(service -> lines.add(
                INDENT + pad(service.name().toLowerCase(Locale.ROOT)) + service.address()));
        StubServers started = stubs.getIfAvailable();
        lines.add("  Partner stubs");
        if (started == null || started.stubs().isEmpty()) {
            lines.add(INDENT + NONE);
        } else {
            started.stubs().forEach(stub -> describe(stub, lines));
        }
        if (started != null && !started.passThrough().isEmpty()) {
            lines.add("  Pass-through clients (calling their configured address)");
            started.passThrough().forEach(name -> lines.add(INDENT + pad(name) + passThroughAddress(started, name)));
        }
        contributors.orderedStream().forEach(contributor -> {
            lines.add("  " + contributor.heading());
            contributor.lines().forEach(line -> lines.add(INDENT + line));
        });
        lines.add("  Seed fixtures (mode " + fixtures.mode().name().toLowerCase(Locale.ROOT) + ")");
        lines.add(INDENT + (fixtures.ran().isEmpty() ? "none ran" : String.join(", ", fixtures.ran())));
        return lines;
    }

    private String passThroughAddress(StubServers started, String name) {
        PartnerStub declared = started.declared().get(name);
        StubAddress address = declared == null ? StubAddress.restClient() : declared.address();
        return address.propertyFor(name)
                .map(property -> environment.getProperty(property, "(" + property + " is not configured)"))
                .orElse("(no address property)");
    }

    private static void describe(RunningStub stub, List<String> lines) {
        String fault = stub.activeFault().map(StubFault::state).orElse("none");
        lines.add(INDENT + pad(stub.name()) + stub.baseUrl() + "   admin " + stub.adminUrl()
                + "   fault " + fault);
        String state = stub.adminUrl() + "/scenarios/" + RunningStub.FAULT_SCENARIO + "/state";
        lines.add(INDENT + INDENT + "set a fault:  curl -X PUT " + state
                + " -H 'Content-Type: application/json' -d '{\"state\":\"unavailable\"}'"
                + "   (unavailable | slow | reset | empty)");
        lines.add(INDENT + INDENT + "clear it:     curl -X PUT " + state);
        stub.definition().summaryLines(stub).forEach(line -> lines.add(INDENT + INDENT + line));
    }

    private static String pad(String name) {
        return String.format(Locale.ROOT, "%-14s", name);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
