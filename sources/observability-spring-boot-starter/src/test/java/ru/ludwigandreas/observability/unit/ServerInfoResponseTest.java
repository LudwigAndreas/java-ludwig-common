package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.observability.core.BuildIdentity;
import ru.ludwigandreas.observability.core.ServiceIdentity;
import ru.ludwigandreas.observability.web.ServerInfoResponse;

/**
 * Pins the body of {@code /server/info} to its allow-list.
 *
 * <p>This is the check behind the {@code server-info} capability's "fixed allow-list" requirement.
 * Every assertion is made on the serialized document rather than on the record's accessors, because
 * the document is what a service publishes, possibly to an anonymous caller.
 */
class ServerInfoResponseTest {

    private static final String FULL_COMMIT = "c1fc5b81ecfa20e1b3418002a5ace90473d6734c";

    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("product-catalog", "commerce", "1.4.2", "prod", "catalog-7d9f-xk2");

    private static final BuildIdentity BUILD =
            new BuildIdentity(FULL_COMMIT, "c1fc5b8", "release-train", "2026-09-16T00:00:00Z", "4711", true);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void carriesExactlyTheFiveMembers() throws JsonProcessingException {
        JsonNode body = mapper.readTree(json(SERVICE, BUILD));

        assertThat(memberNames(body))
                .containsExactlyInAnyOrder("service", "version", "environment", "commit", "built");
        assertThat(body.get("service").asText()).isEqualTo("product-catalog");
        assertThat(body.get("version").asText()).isEqualTo("1.4.2");
        assertThat(body.get("environment").asText()).isEqualTo("prod");
        assertThat(body.get("commit").asText()).isEqualTo("c1fc5b8");
        assertThat(body.get("built").asText()).isEqualTo("2026-09-16");
    }

    @Test
    void withholdsEverythingThatIsAnOperatorFactOrTopology() throws JsonProcessingException {
        String json = json(SERVICE, BUILD);

        // The branch, the CI build number and the full commit; then the namespace and the replica.
        assertThat(json).doesNotContain("release-train", "4711", FULL_COMMIT, "commerce", "catalog-7d9f-xk2");
        // The dirty flag is the only boolean either identity holds.
        assertThat(json).doesNotContain("true", "dirty");
    }

    @Test
    void rendersTheBuildTimeAsItsUtcDate() throws JsonProcessingException {
        BuildIdentity builtLateInTheDay =
                new BuildIdentity(null, null, null, "2026-09-16T23:59:59Z", null, null);
        BuildIdentity builtAheadOfUtc =
                new BuildIdentity(null, null, null, "2026-09-17T01:30:00+03:00", null, null);

        assertThat(mapper.readTree(json(SERVICE, builtLateInTheDay)).get("built").asText())
                .isEqualTo("2026-09-16");
        assertThat(mapper.readTree(json(SERVICE, builtAheadOfUtc)).get("built").asText())
                .isEqualTo("2026-09-16");
    }

    @Test
    void leavesOutABuildTimeThatIsNotAnInstant() throws JsonProcessingException {
        BuildIdentity builtSomehow = new BuildIdentity(null, "c1fc5b8", null, "last tuesday", null, null);

        JsonNode body = mapper.readTree(json(SERVICE, builtSomehow));

        assertThat(body.has("built")).isFalse();
        assertThat(body.get("commit").asText()).isEqualTo("c1fc5b8");
    }

    @Test
    void omitsWhatWasNotResolvedRatherThanWritingNull() throws JsonProcessingException {
        String json = json(SERVICE, BuildIdentity.absent());
        JsonNode body = mapper.readTree(json);

        assertThat(memberNames(body)).containsExactlyInAnyOrder("service", "version", "environment");
        assertThat(json).doesNotContain("null", "unknown");
    }

    @Test
    void isAnEmptyObjectWhenNothingWasResolved() throws JsonProcessingException {
        ServiceIdentity nobody = new ServiceIdentity(null, " ", null, "", null);

        assertThat(json(nobody, BuildIdentity.absent())).isEqualTo("{}");
    }

    private String json(ServiceIdentity service, BuildIdentity build) throws JsonProcessingException {
        return mapper.writeValueAsString(ServerInfoResponse.of(service, build));
    }

    private static List<String> memberNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
