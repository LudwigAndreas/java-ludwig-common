package ru.ludwigandreas.archrules.rules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;

/**
 * There is one answer to "what locale and zone does this caller read in", and nobody asks the JVM.
 *
 * <p>Two rules, for the two halves of the same defect. The platform resolves a caller's locale and zone
 * through {@code ru.ludwigandreas.webcore.preference} - a stored preference, then the request's headers, then
 * the deployment's configuration. The ways that goes wrong are reading the <em>container's</em> answer
 * instead, and declaring a second place for the caller's answer to live.
 *
 * <h2>Why the container's answer is the specific thing forbidden</h2>
 *
 * <p>{@code ZoneId.systemDefault()} is not merely inelegant, it is the hardest class of bug this platform
 * produces, because it is correct on the machine where it would be caught. A developer's laptop and the
 * container share a zone in development and differ in production, so a date rendered from the system default
 * passes every local test, every review that runs it locally, and then reads an hour out - or a day out, at a
 * date boundary - for every user who is not in the datacentre's timezone. {@code Locale.getDefault()} fails
 * the same way with a decimal separator: {@code 1,234.56} and {@code 1 234,56} are the same number read as
 * two different quantities.
 *
 * <h2>What this checks, and what it deliberately does not</h2>
 *
 * <p><b>Checked here:</b> calls to the four JVM-default accessors, and the declaration of a second type
 * holding the caller's locale-and-zone pair. A method call and a field's type are bytecode facts, which is
 * what ArchUnit sees - and the reason neither belongs to Checkstyle: a source-text rule matching
 * {@code systemDefault} would also match the words in the javadoc of the very package that explains why not
 * to call it, of which this change writes several paragraphs.
 *
 * <p><b>Not checked here: whether a mapper that should have used the resolved preferences did.</b> A mapper
 * writing {@code instant.atOffset(ZoneOffset.UTC)} calls no forbidden method and is still wrong. ArchUnit
 * could forbid {@code ZoneOffset.UTC} outright and must not: it is correct in a persistence mapping, a test
 * fixture, an audit record and a Kafka envelope, which together are the large majority of its uses, so the
 * rule would be wrong more often than right. That one is a review question, and it is recorded as a scenario
 * in the {@code user-preference-context} capability so a review has something to point at rather than a
 * recollection.
 *
 * <p><b>Also not checked: whether the configured default zone is the right zone for the deployment.</b> An
 * operator who set {@code UTC} because the business runs on UTC and one who set it because it was in the
 * example are indistinguishable from any artifact a build can read.
 */
public final class PresentationRules implements ArchitectureRuleSet {

    /** Nothing presents a time or a number from a JVM default. */
    public static final RuleId NO_AMBIENT_DEFAULT_LOCALE_OR_ZONE =
            RuleId.of(RuleGroup.PRESENTATION, "no-ambient-default-locale-or-zone");

    /** Nothing declares a second type holding the caller's locale and zone. */
    public static final RuleId NO_SECOND_CALLER_PREFERENCE_TYPE =
            RuleId.of(RuleGroup.PRESENTATION, "no-second-caller-preference-type");

    /** The package that legitimately owns the platform's caller-preference types. */
    private static final String PREFERENCE_PACKAGES = "ru.ludwigandreas.webcore.preference..";

    private static final String LOCALE_TYPE = "java.util.Locale";

    private static final String ZONE_ID = "java.time.ZoneId";

    /**
     * The JVM-default accessors, keyed by declaring type.
     *
     * <p>{@code Clock.systemDefaultZone()} is here and {@code Clock.systemUTC()} is not. The difference is
     * what the clock is used for: a {@code Clock} injected to make a service testable is read for an instant,
     * and an instant has no zone, so {@code systemUTC()} is the right choice and is in use across this
     * platform. {@code systemDefaultZone()} produces a clock whose zone is the container's, which is the one
     * property of a clock that must never be inherited from the environment.
     *
     * <p>{@code TimeZone.getDefault()} is listed beside {@code ZoneId.systemDefault()} because they are the
     * same mistake in the two date-time APIs, and a rule that caught only the modern one would be satisfied
     * by writing the legacy one.
     */
    private static final Map<String, Set<String>> JVM_DEFAULTS = Map.of(
            LOCALE_TYPE, Set.of("getDefault"),
            "java.util.TimeZone", Set.of("getDefault"),
            ZONE_ID, Set.of("systemDefault"),
            "java.time.Clock", Set.of("systemDefaultZone"));

    /**
     * The types that may read a JVM default, and why each one may.
     *
     * <p>Named here, in the rule, rather than suppressed at the call site, for the reason
     * {@link CachingRules} gives about its own list: the set of exceptions should be one list somebody can
     * read, and adding to it should be a visible edit to a shared file rather than an annotation in a module
     * nobody else opens.
     *
     * <p>The list is deliberately short and deliberately does not include
     * {@code ConfiguredPreferenceSource}, which is the type most people assume would need it.
     * It does not: the deployment's default zone is a property, read by name from configuration, which is the
     * entire point of that class existing.
     */
    private static final List<String> EXEMPT_TYPES = List.of(
            // Spring's own holder falls back to the JVM default when nothing has installed a process-wide
            // one; this is the single place the platform installs one, so that the fallback is never reached
            // in a wired application and is a defined value rather than the environment's in a unit test.
            "ru.ludwigandreas.webcore.preference.UserPreferenceDefaults");

    /**
     * Fields that identify whose preferences a type holds, as a secondary exemption.
     *
     * <p>Secondary because the clause that does the work is "and nothing else"; see
     * {@link #noSecondCallerPreferenceType()}. This list is what also lets through a per-subject type that
     * happens to carry only the pair plus an identifier, which is a shape nothing in the repository has today
     * and which a reader of the rule would otherwise expect to be caught.
     */
    private static final Set<String> SUBJECT_FIELD_NAMES =
            Set.of("subject", "subjectid", "userid", "recipient", "recipientid", "principal", "principalid",
                    "tenantid", "actor", "actorid");

    @Override
    public RuleGroup group() {
        return RuleGroup.PRESENTATION;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(
                ArchitectureRule.of(NO_AMBIENT_DEFAULT_LOCALE_OR_ZONE, noAmbientDefaultLocaleOrZone(),
                        "Read ru.ludwigandreas.webcore.preference.UserPreferences.current() instead of the"
                                + " JVM's default locale, timezone or clock zone. The JVM default is the"
                                + " container's, which is UTC in the datacentre and the developer's own zone"
                                + " on a laptop - so a date rendered from it passes every test run locally"
                                + " and reads an hour out, or a day out at a date boundary, for every user"
                                + " who is not in the datacentre's timezone. For a Clock that only needs an"
                                + " instant, inject Clock.systemUTC(); an instant has no zone."),
                ArchitectureRule.of(NO_SECOND_CALLER_PREFERENCE_TYPE, noSecondCallerPreferenceType(),
                        "Use ru.ludwigandreas.webcore.preference.UserPreferences instead of declaring a"
                                + " second type holding the caller's locale and zone. One contract means one"
                                + " precedence order - a stored choice, then the request's headers, then"
                                + " configuration - and a second type is a second precedence order that will"
                                + " differ exactly where it matters. If the pair belongs to a NAMED SUBJECT"
                                + " rather than to the current caller, say so in the type: a field naming"
                                + " whose preferences these are exempts it, because a per-subject lookup on"
                                + " a worker thread is a different problem with no ambient answer."));
    }

    /**
     * Nothing calls the JVM-default accessors.
     *
     * <p>Expressed as calls rather than as a dependency on {@code Locale} or {@code ZoneId}, which every class
     * in this package legitimately has. It is the specific static accessors that are the defect, not the
     * types.
     *
     * <p>{@code classes().should(notCallMethods(...))} and not {@code noClasses().should(...)}, and the
     * distinction is not cosmetic. {@code noClasses()} wraps the condition in ArchUnit's {@code never()},
     * which <em>inverts</em> each event, so a condition that reports only violations - as every
     * {@code ArchitectureConditions.notXxx} helper here does - produces exactly zero findings under it. The
     * rule still appears in the report, still says it passed, and checks nothing. Two rules in this library
     * were written that way and were silently inert until this one was added and tested against a fixture
     * that provably broke it; see {@code PresentationRulesTest}, which asserts the violation is reported
     * rather than asserting only that the rule ran.
     */
    private static ArchRule noAmbientDefaultLocaleOrZone() {
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(PREFERENCE_PACKAGES)))
                .and(DescribedPredicate.not(exempt()))
                .should(ArchitectureConditions.notCallMethods(JVM_DEFAULTS,
                        "the JVM's default locale, timezone or clock zone"))
                .as("Nothing presents a time or a number from a JVM default");
    }

    /**
     * No type outside the preference package <em>is</em> the caller's locale-and-zone pair.
     *
     * <p>The discriminator is that the type's declared instance fields are <b>exactly</b> a {@code Locale}
     * and a {@code ZoneId} and nothing else. That is narrow on purpose, and the narrowness was measured
     * rather than chosen: the first version of this rule asked only for both fields to be present, and the
     * full reactor build reported {@code notification-service}'s {@code RecipientPreferences} and
     * {@code StoredPreferences} - which carry the pair alongside a {@code QuietHours}, a {@code DigestMode}
     * and an {@code OptOutMatrix}, are built per recipient on a queue worker where there is no ambient caller
     * at all, and are resolved through {@code SettingsLookup.getAll(subject)} for each of possibly hundreds of
     * recipients in one dispatch. Put an ambient static accessor on that path and it answers with the worker's
     * defaults or with whichever recipient was processed last. The same "both fields present" rule would also
     * have flagged {@code export}'s {@code ReportRequest}, a fifteen-field request DTO.
     *
     * <p>So: a type with a locale alone is a localization concern and not this; a type with a zone alone is a
     * scheduling concern and not this; a type carrying the pair <em>plus its own state</em> is a domain object
     * that needs a locale and a zone, which is every legitimate holder of them. What the rule catches is a
     * type that exists <em>in order to be</em> the pair, which is what a restatement is and what
     * {@code export}'s {@code RenderContext} was before it was changed to hold a {@code UserPreferences}.
     *
     * <p><b>The limit, which cannot be checked:</b> a third field evades this rule. That is accepted for the
     * same reason {@link CachingRules}'s name heuristic accepts its own: the rule exists to catch the
     * reasonable local decision, not the determined evasion, and a rule broad enough to catch the second
     * would flag every domain object in the platform and be switched off within a quarter. The subject-field
     * exemption below is kept as a second clause so that the intent survives a later widening.
     */
    private static ArchRule noSecondCallerPreferenceType() {
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(PREFERENCE_PACKAGES)))
                .should(beTheCallerPreferencePair())
                .as("No module declares a second caller-preference type");
    }

    private static DescribedPredicate<JavaClass> exempt() {
        return DescribedPredicate.describe("types exempted by name - see EXEMPT_TYPES",
                javaClass -> EXEMPT_TYPES.contains(javaClass.getName()));
    }

    private static ArchCondition<JavaClass> beTheCallerPreferencePair() {
        return new ArchCondition<>("be nothing but the caller's locale and zone") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean locale = false;
                boolean zone = false;
                boolean subject = false;
                int other = 0;
                for (JavaField field : item.getFields()) {
                    if (field.getModifiers().contains(JavaModifier.STATIC)) {
                        // A constant is not state. Excluded so that a record declaring its own NONE or
                        // EMPTY instance - which several in this platform do - is judged on its components.
                        continue;
                    }
                    String type = field.getRawType().getName();
                    if (LOCALE_TYPE.equals(type)) {
                        locale = true;
                    } else if (ZONE_ID.equals(type)) {
                        zone = true;
                    } else {
                        other++;
                        subject = subject || namesASubject(field);
                    }
                }
                boolean matches = locale && zone && other == 0 && !subject;
                events.add(new SimpleConditionEvent(item, matches,
                        item.getName() + (matches ? " is" : " is not")
                                + " a second type whose whole content is the caller's locale and zone"));
            }
        };
    }

    /**
     * Whether a field says whose preferences the type holds.
     *
     * <p>By name rather than by type, because the identifier is a {@code String} or a {@code UUID} in every
     * case and a type test would catch nothing. Matched case-insensitively and with underscores removed, so
     * {@code recipient_id}, {@code recipientId} and {@code RECIPIENT_ID} are one answer rather than three
     * chances to miss.
     */
    private static boolean namesASubject(JavaField field) {
        String name = field.getName().toLowerCase(Locale.ROOT).replace("_", "");
        return SUBJECT_FIELD_NAMES.contains(name);
    }
}
