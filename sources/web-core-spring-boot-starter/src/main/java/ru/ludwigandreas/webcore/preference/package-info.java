/**
 * The platform's contract for a caller's presentation preferences: what language to address them in,
 * and what zone to render their times in.
 *
 * <h2>What problem this solves</h2>
 *
 * <p>Before this package, {@code LocaleContextHolder.getLocale()} was correct on a request thread
 * and {@code LocaleContextHolder.getTimeZone()} was the container's zone, because
 * {@code AcceptHeaderLocaleResolver} is a plain {@code LocaleResolver} and never publishes a
 * {@code TimeZoneAwareLocaleContext}. A mapper turning a {@code service.model} into a
 * {@code web.dto} therefore had no standard way to render an {@code Instant} in the caller's zone,
 * and the two options it did have were to thread a parameter through every mapping method in the
 * service or to call {@code ZoneId.systemDefault()} and be wrong. Both happened.
 *
 * <h2>A contract, not a store</h2>
 *
 * <p>Nothing here persists anything. {@code user-settings-spring-boot-starter} owns storage,
 * layering, validation, caching, audit and the {@code /me/settings} API, and contributes a
 * {@link ru.ludwigandreas.webcore.preference.UserPreferenceSource} that reads it. That is the same
 * construction {@code ru.ludwigandreas.webcore.operation} uses and for the same reason: it is what
 * keeps {@code web-core} free of a persistence dependency it must not have, and it is why the SPI is
 * declared here and implemented there rather than the other way round.
 *
 * <h2>The caller, and not a subject</h2>
 *
 * <p>This is the <em>ambient</em> context of whoever is making the current request. It is not a
 * general way to ask "what are user X's preferences" - that question is
 * {@code SettingsLookup.getAll(subject)}, it takes a subject because there is no ambient one, and it
 * is what a queue worker fanning out a notification to a hundred recipients must use.
 * {@code notification-service}'s {@code RecipientPreferences} is that shape and is deliberately not
 * consolidated into this one: a static ambient accessor on a worker thread resolves the wrong
 * person's preferences or nobody's. {@code RuleGroup.PRESENTATION}'s
 * {@code noSecondCallerPreferenceType} rule is written so that it does not fire on the per-subject
 * shape, and its javadoc states the distinction, because the distinction is the rule.
 *
 * <h2>Where to start</h2>
 *
 * <ul>
 *   <li>{@link ru.ludwigandreas.webcore.preference.UserPreferences} - the value, the ambient
 *       accessor, the binding scope for a worker thread, and the derived formatters.</li>
 *   <li>{@link ru.ludwigandreas.webcore.preference.UserPreferenceFormatter} - what a mapper names
 *       in {@code @Mapper(uses = ...)}, which is the answer to "how does a DTO get this".</li>
 *   <li>{@link ru.ludwigandreas.webcore.preference.UserPreferenceSource} - the SPI, the per-dimension
 *       resolution and why a stored choice outranks a request header.</li>
 *   <li>{@link ru.ludwigandreas.webcore.preference.UserPreferenceLocaleContextResolver} - how the
 *       zone reaches {@code LocaleContextHolder}, and why this takes the {@code localeResolver} bean
 *       name rather than sitting beside it.</li>
 * </ul>
 */
package ru.ludwigandreas.webcore.preference;
