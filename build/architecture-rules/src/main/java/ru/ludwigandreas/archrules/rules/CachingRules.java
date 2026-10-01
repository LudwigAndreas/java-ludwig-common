package ru.ludwigandreas.archrules.rules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.regex.Pattern;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;

/**
 * There is one caching primitive, and nobody builds a second one.
 *
 * <p>This rule set is the durable half of a consolidation, exactly as {@link AuditRules} is. Two modules had
 * independently produced the identical triad - an SPI, a Caffeine implementation and a no-op - differing only
 * in their key and value types. They had the same five operations, the same
 * {@code (Duration ttl, long maximumSize)} constructor, the same paragraph explaining why expiry is
 * {@code expireAfterWrite} and not {@code expireAfterAccess}, and the same paragraph making the
 * thundering-herd argument in nearly the same words. Both were reasonable local decisions: a module needed a
 * cache, Caffeine is the obvious answer, and nobody was in a position to see the other one.
 *
 * <p>What the duplication cost was not the lines. It was that neither implementation called
 * {@code recordStats()}, so hit ratio was unobservable across the whole platform; that neither had negative
 * caching, stampede protection beyond one process, or a key namespace; and that the one difference between
 * them that genuinely mattered - one TTL was a revocation window and the other was a throughput knob - was
 * written down only in prose. Replacing them fixes the state of the code; only a rule stops the third copy
 * being written next quarter, which is why the rule is the real deliverable rather than the refactor.
 *
 * <h2>What this checks, and what it deliberately does not</h2>
 *
 * <p><b>Checked here:</b> that nothing outside the cache module depends on Caffeine's builder, and that
 * nothing outside it declares an interface shaped like a cache. Both are structure - a dependency and a
 * type's shape - which is what ArchUnit sees.
 *
 * <p><b>Not checked here: whether a cache's TTL is a sensible number, or whether its declared purpose is the
 * right one.</b> Neither is expressible structurally: the TTL is configuration, and the purpose is a
 * statement about what the cached value <em>means</em> that no analysis can verify. They are covered where
 * they can be - the TTL by {@code cache-spring-boot-starter}'s startup validator, which refuses a
 * security-purpose TTL above the configured ceiling, and the purpose by the module author writing it down.
 *
 * <p><b>Also not checked: whether a module that should cache does.</b> "This lookup is hot enough to deserve
 * a cache" is a performance judgment, not a structural property.
 */
public final class CachingRules implements ArchitectureRuleSet {

    /** Nothing outside the cache module builds a Caffeine cache of its own. */
    public static final RuleId NO_PRIVATE_CAFFEINE_CACHE =
            RuleId.of(RuleGroup.CACHING, "no-private-caffeine-cache");

    /** Nothing outside the cache module declares a cache SPI of its own. */
    public static final RuleId NO_SECOND_CACHE_SPI =
            RuleId.of(RuleGroup.CACHING, "no-second-cache-spi");

    /** The package that legitimately owns the platform's caching types. */
    private static final String CACHE_PACKAGES = "ru.ludwigandreas.cache..";

    /** Caffeine's builder, named as a string so this jar does not have to depend on Caffeine. */
    private static final String CAFFEINE_BUILDER = "com.github.benmanes.caffeine.cache.Caffeine";

    /**
     * The one type outside the cache module that may build its own Caffeine cache, and why.
     *
     * <p>{@code EnrichmentCache} is scoped to a single report run and bounded by it. Moving it onto the
     * platform's named caches is not a consolidation, it is a <b>correctness change</b>: a report is a
     * point-in-time statement, two runs an hour apart must each see the partner's data as it was when that run
     * started, and a cache with application-wide lifetime would make the second one silently a mixture. It
     * also has no TTL at all, by design - the run is the lifetime - so it has nothing for
     * {@link ru.ludwigandreas.archrules.rules.CachingRules} to govern and nothing the named-cache model would
     * improve. It already calls {@code recordStats()}, so it is not an instance of the mistake this rule
     * exists to prevent.
     *
     * <p>Named here, in the rule, rather than suppressed at the call site, so that the list of exceptions is
     * one list somebody can read - and so that adding to it is a visible edit to a shared file rather than an
     * annotation in a module nobody else looks at. Two other types in this repository are commonly mistaken
     * for caches and are not exempted because they do not build a Caffeine cache at all:
     * {@code rest-client}'s {@code CachedToken}, a single-value OAuth token holder whose refresh-before-expiry
     * semantics are not cache semantics, and {@code idempotency}'s {@code CachedBodyRequest}, which is a
     * servlet request wrapper despite the name.
     */
    private static final List<String> EXEMPT_TYPES =
            List.of("ru.ludwigandreas.export.enrich.EnrichmentCache");

    /**
     * A type name that reads as a cache seam.
     *
     * <p>Names rather than method shapes, for the reason {@link AuditRules} gives about audit sinks: the shape
     * of a cache - a keyed get and a put - is also the shape of every registry, repository and map wrapper in
     * the platform, and a rule keyed on that would flag most of them. What made the two deleted triads
     * recognisable as the same mistake is that each was called a cache: {@code AuthorityCache},
     * {@code SettingsCache}.
     */
    private static final Pattern CACHE_SEAM_NAME = Pattern.compile(
            "(?i).*cache$|(?i).*cache(store|repository|holder|registry)$");

    @Override
    public RuleGroup group() {
        return RuleGroup.CACHING;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(
                ArchitectureRule.of(NO_PRIVATE_CAFFEINE_CACHE, noPrivateCaffeineCache(),
                        "Resolve a named cache from ru.ludwigandreas.cache.api.LudwigCacheRegistry instead of"
                                + " building a Caffeine cache here. A hand-built one is a cache with none of"
                                + " the governance the platform applies: no declared meaning for its TTL and"
                                + " therefore no startup ceiling on it, no recorded statistics and therefore"
                                + " no observable hit ratio, no negative-caching rule that distinguishes"
                                + " absent from failed, and no eviction fan-out if it is ever shared."),
                ArchitectureRule.of(NO_SECOND_CACHE_SPI, noSecondCacheSpi(),
                        "Use ru.ludwigandreas.cache.api.LudwigCache instead of declaring a cache SPI of your"
                                + " own. Keep the typed key and value - they are what makes a call site"
                                + " readable - and declare a CacheDefinition, as AuthorityCaches and"
                                + " SettingsCaches do. A second SPI means a second Caffeine builder, a second"
                                + " TTL whose meaning nobody has stated, and a third copy of the same three"
                                + " classes."));
    }

    /**
     * Nothing outside the cache module depends on Caffeine's builder.
     *
     * <p>Expressed as a dependency on the {@code Caffeine} type rather than as a call to
     * {@code Caffeine.newBuilder()}, because the dependency is the thing that cannot be hidden: a class that
     * reaches the builder through a static import, a helper or a method reference still carries
     * {@code Caffeine} in its constant pool.
     */
    private static ArchRule noPrivateCaffeineCache() {
        DescribedPredicate<JavaClass> caffeine = DescribedPredicate.describe(
                "Caffeine's builder - caching is the platform's",
                javaClass -> CAFFEINE_BUILDER.equals(javaClass.getName()));
        // classes(), not noClasses(): noClasses() wraps the condition in ArchUnit's never(), which inverts
        // every event - and notDependOnClassesThat reports only violations, so under noClasses() this rule
        // reported nothing at all. It was inert from the day it was written until PresentationRules was
        // added and the polarity was measured against a fixture. See PresentationRules.
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(CACHE_PACKAGES)))
                .and(DescribedPredicate.not(exempt()))
                .should(ArchitectureConditions.notDependOnClassesThat(caffeine,
                                "a Caffeine cache of their own")
                        .as("not build a Caffeine cache of their own"))
                .as("Only the cache module builds a Caffeine cache");
    }

    /**
     * No interface outside the cache module is named like a cache and shaped like one.
     *
     * <p>Interfaces only, and only the ones somebody could implement, for the reason {@link AuditRules} gives:
     * a concrete class named {@code SomethingCache} is usually a caller or an adapter, and forbidding the name
     * would forbid a reasonable name for a class that does the right thing. What must not exist is a second
     * <em>seam</em> - the point at which a module invites a deployment to publish its own cache, which is
     * where a cache stops being governed by the platform.
     */
    private static ArchRule noSecondCacheSpi() {
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(CACHE_PACKAGES)))
                .and().areInterfaces()
                .should(beNamedAndShapedLikeACache())
                .as("No module declares a cache SPI of its own");
    }

    private static DescribedPredicate<JavaClass> exempt() {
        return DescribedPredicate.describe("run-scoped caches exempted by name - see EXEMPT_TYPES",
                javaClass -> EXEMPT_TYPES.contains(javaClass.getName()));
    }

    private static ArchCondition<JavaClass> beNamedAndShapedLikeACache() {
        return new ArchCondition<>("be named like a cache and declare get/put/evict") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean matches = CACHE_SEAM_NAME.matcher(item.getSimpleName()).matches()
                        && declaresACacheShape(item);
                events.add(new SimpleConditionEvent(item, matches,
                        item.getName() + (matches ? " is" : " is not") + " a second cache SPI"));
            }
        };
    }

    /**
     * Whether the interface has both halves of a cache: a keyed read and a way to discard.
     *
     * <p>The name test alone would flag a read-through facade or a query-side type that merely mentions
     * caching. Requiring a {@code get}-like method <em>and</em> an {@code evict}-like one is what narrows it to
     * the shape both deleted SPIs had, and it is also what lets a legitimate named-cache <em>accessor</em>
     * through.
     */
    private static boolean declaresACacheShape(JavaClass item) {
        boolean reads = false;
        boolean discards = false;
        for (JavaMethod method : item.getMethods()) {
            String name = method.getName();
            reads = reads || name.startsWith("get") && !method.getRawParameterTypes().isEmpty();
            discards = discards || name.startsWith("evict") || name.startsWith("invalidate");
        }
        return reads && discards;
    }
}
