package ru.ludwigandreas.export.api;

/**
 * Whose credentials an enrichment stage's partner calls carry.
 *
 * <h2>Why this is a choice and not a rule</h2>
 *
 * <p>Both answers are legitimate, they answer different questions, and an estate has both. Fixing one
 * of them in the module would be this module deciding another team's trust boundary.
 *
 * <p><b>{@link #SERVICE_ACCOUNT}</b> is a <em>configured integration</em>: the deployment has
 * provisioned this service's own credentials against the partner, and the partner trusts this service
 * rather than the person behind the request. It is the only answer that works for a run that is not
 * happening on a request thread, which is most of them - a deferred run, a cron subscription, a
 * reclaimed attempt after an instance died - because there is no user token in scope to relay.
 *
 * <p><b>{@link #REQUESTER}</b> relays the caller's own access token, so the partner applies its own
 * row-level scoping and a report can never show a user something the partner would refuse them
 * directly. The cost is that it only exists while the request thread does: the token was never stored
 * (deliberately - a stored bearer token is a credential at rest with a long life), so a run that is
 * deferred cannot relay it.
 *
 * <h2>What enforces the choice</h2>
 *
 * <p>Nothing here sends a header. The identity is applied by the named {@code @LudwigRestClient} the
 * stage declares, through its {@code auth.type} - {@code oauth2-client-credentials} for a service
 * account, {@code oauth2-token-relay} for the requester. Declaring it on the stage as well is what
 * makes the two checkable against each other: a stage saying one thing while its client is configured
 * for the other fails the application context, naming both, rather than quietly producing a file
 * containing whatever the partner shows a service account.
 *
 * <p>The default is {@link #SERVICE_ACCOUNT}, settable with {@code ludwig.export.enrichment.call-as}
 * and overridable per stage. It is the default because it is the one that works for every run shape;
 * a default of {@code REQUESTER} would make every report that grew past the synchronous threshold
 * stop working, which is a failure mode that appears in production and not in testing.
 */
public enum CallIdentity {

    /**
     * This service's own credentials, whatever the deployment configured for the named client.
     *
     * <p>Works for every run, synchronous or deferred. The partner sees one caller regardless of who
     * asked, so whatever narrowing the report needs has to be in the report: the base query's data
     * scope, the stage's key extractor, the columns' {@code visibleFor}. That is a real obligation and
     * not a footnote - a stage that hands a service account's view of a partner to a user who could
     * not have read it directly is a data leak this module cannot detect for you.
     */
    SERVICE_ACCOUNT,

    /**
     * The requester's own access token, relayed to the partner.
     *
     * <p>The partner applies its own authorization, which is why this is worth the constraint it
     * carries: a report cannot become the one bulk read in the estate that bypasses a partner's
     * scoping.
     *
     * <p>The constraint is that the run must be happening on the request thread. A definition with a
     * {@code REQUESTER} stage is refused at request time when its estimate says it would be deferred,
     * and a deferred attempt of one fails with a distinct code rather than falling back to a service
     * account - a silent fallback would produce exactly the file this option exists to prevent.
     */
    REQUESTER
}
