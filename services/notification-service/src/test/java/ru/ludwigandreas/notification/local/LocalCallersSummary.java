package ru.ludwigandreas.notification.local;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.testsupport.local.SummaryContributor;
import ru.ludwigandreas.testsupport.local.stub.StubServers;

/**
 * Prints, for each seeded caller, a command that calls the running service as them.
 *
 * <p>This is what turns "it started" into "I am at my breakpoint": the port is allocated, the token
 * has to be signed by a key that exists only in this JVM, and nobody should have to assemble either
 * by hand. The tokens are long-lived on purpose - a debugging session outlasts five minutes - and
 * are worth nothing outside this process.
 */
public class LocalCallersSummary implements SummaryContributor {

    private static final Duration PRINTED_TOKEN_LIFETIME = Duration.ofHours(12);

    private final Environment environment;
    private final StubServers stubs;

    public LocalCallersSummary(Environment environment, StubServers stubs) {
        this.environment = environment;
        this.stubs = stubs;
    }

    @Override
    public String heading() {
        return "Call the service (tokens valid " + PRINTED_TOKEN_LIFETIME.toHours() + "h, this process only)";
    }

    @Override
    public List<String> lines() {
        String service = "http://localhost:" + environment.getProperty("local.server.port", "8080");
        IdentityProviderStub issuer =
                (IdentityProviderStub) stubs.stub(IdentityProviderStub.NAME).definition();
        List<String> lines = new ArrayList<>();
        lines.add("api docs      " + service + "/swagger-ui/index.html");
        for (LocalCaller caller : LocalCaller.all()) {
            String path = caller.roles().isEmpty() ? "/api/v1/inbox" : "/api/v1/notifications/deliveries";
            lines.add(caller.subject() + ":  curl -H 'Authorization: Bearer "
                    + issuer.tokenFor(caller, PRINTED_TOKEN_LIFETIME) + "' " + service + path);
        }
        return lines;
    }
}
