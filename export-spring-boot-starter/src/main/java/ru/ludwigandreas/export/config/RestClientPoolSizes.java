package ru.ludwigandreas.export.config;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * What this module needs to know about a named {@code @LudwigRestClient}: how large its pool is, and
 * how it authenticates.
 *
 * <h2>Why this is an interface and not a lookup the validator performs</h2>
 *
 * <p>{@link ExportConfigurationValidator} refuses a definition whose enrichment stage asks for more
 * concurrency than the REST client it calls through can carry, because the extra calls do not fail -
 * they queue inside the pool, and a bounded fan-out silently becomes an unbounded wait. Making that
 * check requires knowing a number that belongs to {@code rest-client-spring-boot-starter}, which is
 * an optional dependency of this module.
 *
 * <p>So the number arrives through this seam. When the starter is absent, {@link #UNKNOWN} answers
 * empty for every name, and the validator reads that as "no such client" - which is the correct
 * reading: a stage naming a client that nothing configures would have its calls made by whatever the
 * enricher opened instead, outside the platform's pools, timeouts and breakers.
 *
 * <p>The same seam answers a second question - how the named client authenticates - because the two
 * are asked together and by the same validator; see {@link #authTypeOf}.
 *
 * <p>It is also the seam a service overrides when it reaches partners through something this module
 * has never heard of. Publishing a bean of this type replaces the resolution wholesale, which is
 * preferable to the alternative a missing seam produces: an author who cannot satisfy the check
 * dropping {@code restClient} from the stage, and losing the check for every other stage with it.
 */
public interface RestClientPoolSizes {

    /**
     * Answers empty for every name, for a deployment with no REST-client starter on the classpath.
     *
     * <p>Deliberately not "answers a generous number", which would pass every check including the
     * one the validator exists for.
     */
    RestClientPoolSizes UNKNOWN = new RestClientPoolSizes() {
        @Override
        public OptionalInt forClient(String clientName) {
            return OptionalInt.empty();
        }

        @Override
        public Optional<String> authTypeOf(String clientName) {
            return Optional.empty();
        }
    };

    /**
     * The concurrency the named client can actually sustain.
     *
     * @param clientName the {@code @LudwigRestClient} name a stage declares
     * @return the client's per-route connection ceiling, or empty when no client is configured under
     *         that name
     */
    OptionalInt forClient(String clientName);

    /**
     * How the named client authenticates, as {@code auth.type} spells it.
     *
     * <p>Asked so that a stage's declared {@link ru.ludwigandreas.export.api.CallIdentity} can be
     * checked against it. The two are the same decision made in two places - the definition says whose
     * data the report is scoped to, the deployment says whose credentials reach the partner - and the
     * failure when they disagree is silent: a stage that believes it relays the requester's token, whose
     * client is configured with the service's own credentials, produces a file containing whatever the
     * partner shows this service. Nothing in the file says so.
     *
     * <p>Empty when no client is configured under that name, which the validator already reports
     * separately, or when this resolver cannot tell - a service that overrode the seam for a transport
     * of its own. An empty answer is not treated as a mismatch: refusing to start over a check that
     * could not be performed would punish the deployment that extended the module correctly.
     *
     * @param clientName the {@code @LudwigRestClient} name a stage declares
     * @return the configured {@code auth.type}, or empty when it is unknown
     */
    Optional<String> authTypeOf(String clientName);
}
