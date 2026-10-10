package ru.ludwigandreas.observability.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import ru.ludwigandreas.observability.core.BuildIdentity;
import ru.ludwigandreas.observability.core.ServiceIdentity;

/**
 * What a user interface may show about the service that answered it: the body of
 * {@code GET /server/info}.
 *
 * <h2>The components are an allow-list</h2>
 *
 * <p>These five and no others. The set is specified by the {@code server-info} capability and pinned
 * by {@code ServerInfoResponseTest}, which fails when a member appears or disappears. It is what lets
 * a service list the path among its public paths: the branch, the dirty flag, the CI build number
 * and the full commit id are operator facts and stay on the management endpoint, and the instance
 * names a replica, which is deployment topology.
 *
 * <p>That is also why this is a record of its own, built by {@link #of} one component at a time,
 * rather than {@link BuildIdentity} serialized with some fields hidden. Hiding is a deny-list: a
 * component added to that record later would be public the day it was added. Here it is invisible
 * until someone writes the line that exposes it.
 *
 * <h2>Absent means absent</h2>
 *
 * <p>An unresolved component is left out of the body rather than written as {@code null} or as a
 * placeholder, the rule {@link BuildIdentity} already follows. A process with no identity at all
 * serializes to {@code {}}.
 *
 * @param service     the service's name
 * @param version     the deployed version
 * @param environment the deployment tier
 * @param commit      the abbreviated commit the artifact was built from
 * @param built       the day the artifact was built, as {@code YYYY-MM-DD} in UTC. Text rather than a
 *                    date type so that its form does not depend on how the service has configured
 *                    its own {@code ObjectMapper}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerInfoResponse(String service, String version, String environment, String commit, String built) {

    /** Maps the two resolved identities onto the five published components, by name. */
    public static ServerInfoResponse of(ServiceIdentity service, BuildIdentity build) {
        return new ServerInfoResponse(service.name(), service.version(), service.environment(),
                build.abbreviatedCommitId(), buildDate(build.buildTimestamp()));
    }

    /**
     * The UTC calendar date of the build timestamp, or {@code null} when there is none.
     *
     * <p>A date, because the platform's build records the time at day precision and a time of day
     * would be a value that was never measured. UTC as a constant, not the JVM's zone and not the
     * caller's: this is a fact about the build, not a moment presented to a user. Text that is not
     * an instant - possible for an artifact built outside the service parent - yields no date rather
     * than being passed through, since what is published is specified as a date.
     */
    private static String buildDate(String timestamp) {
        if (timestamp == null) {
            return null;
        }
        try {
            return Instant.parse(timestamp).atOffset(ZoneOffset.UTC).toLocalDate().toString();
        } catch (DateTimeParseException notAnInstant) {
            return null;
        }
    }
}
