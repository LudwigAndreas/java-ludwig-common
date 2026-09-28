package ru.ludwigandreas.archrules.rules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;

/**
 * There is one audit trail, and nobody declares a second one.
 *
 * <p>This rule set is the durable half of a consolidation. Nine modules of this platform had each
 * invented an audit mechanism - nine SPIs, seven SLF4J implementations, three persistent stores with
 * three schemas - and every one of them was a reasonable local decision: a module needed somewhere for
 * its trail to go, an SPI with a shipped default is the idiomatic answer, and nobody was in a position
 * to see the other eight. Replacing them with {@code audit-core} fixes the state of the code; only a
 * rule stops the same reasonable local decision being made again next quarter, which is why the rule
 * is the real deliverable rather than the refactor.
 *
 * <h2>What this checks, and what it deliberately does not</h2>
 *
 * <p><b>Checked here:</b> that no type outside the audit module declares an interface shaped like an
 * audit sink. That is structure - a type's name, its modifiers, its package, its method signatures -
 * which is exactly what ArchUnit sees.
 *
 * <p><b>Not checked here: a second redaction mask constant.</b> A mask is a string literal, and
 * ArchUnit reads compiled bytecode, where a {@code static final String}'s <em>value</em> is a
 * constant-pool entry that {@code JavaField} does not expose - so "no field whose value is
 * {@code ****}" is not expressible, and a rule that approximated it by field <em>name</em>
 * ({@code MASK}, {@code REDACTED}) would miss the one spelled {@code HIDDEN} and flag a legitimate
 * unrelated constant. It belongs to Checkstyle, which does see source text, and it is implemented
 * there as the {@code SecondRedactionMask} rule in {@code checkstyle-rules}. This split is the same
 * one the repository already draws: {@code architecture-rules} owns structure and dependencies,
 * {@code checkstyle-rules} owns source text, SonarQube owns bugs and security.
 *
 * <p><b>Also not checked: that a module actually audits what it should.</b> "Every state mutation emits
 * an audit event" needs to know which methods mutate state, which is a semantic judgment no structural
 * rule can make. What can be checked structurally is what this one checks: that when a module does
 * audit, it does so through the platform's type.
 */
public final class AuditRules implements ArchitectureRuleSet {

    /** No module declares an audit SPI of its own; there is one, in {@code audit-core}. */
    public static final RuleId NO_SECOND_AUDIT_SPI =
            RuleId.of(RuleGroup.AUDIT, "no-second-audit-spi");

    /** Audit events are recorded through the platform sink rather than a logger named for auditing. */
    public static final RuleId NO_PRIVATE_AUDIT_LOGGER_NAMES =
            RuleId.of(RuleGroup.AUDIT, "no-private-audit-logger-names");

    /** The package that legitimately declares the platform's audit types. */
    private static final String AUDIT_PACKAGES = "ru.ludwigandreas.audit..";

    /**
     * A type name that reads as an audit seam.
     *
     * <p>Names rather than method shapes, because the shape of an audit sink - one void method taking
     * one object - is also the shape of every listener, consumer and callback in the platform, and a
     * rule keyed on that would flag most of them. What makes these nine recognisable as the same
     * mistake is that each was called an audit something: {@code ExportAuditSink},
     * {@code IngestAuditLogger}, {@code HotReloadAuditLogger}, {@code OutboxAuditLogger},
     * {@code ReconciliationAuditLogger}, {@code AuditEventEmitter}, {@code AccessAuditLogger}.
     */
    private static final Pattern AUDIT_SEAM_NAME = Pattern.compile(
            "(?i).*audit.*(sink|logger|emitter|recorder|publisher|writer|appender|trail)"
                    + "|(?i).*(sink|logger|emitter|recorder)audit.*");

    /** Logger names that mean "this is my module's private audit stream". */
    private static final Pattern AUDIT_LOGGER_NAME = Pattern.compile("(?i).*\\.audit(\\..*)?$");

    @Override
    public RuleGroup group() {
        return RuleGroup.AUDIT;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(
                ArchitectureRule.of(NO_SECOND_AUDIT_SPI, noSecondAuditSpi(),
                        "Record events through ru.ludwigandreas.audit.AuditSink instead of declaring an"
                                + " audit SPI of your own. Keep your module's typed event record - it is the"
                                + " readable authoring surface - and give it a toAuditEvent() that returns"
                                + " the platform envelope, as ExportAuditEvent and AccessDecision do. A"
                                + " second SPI means a second destination, a second retention and a second"
                                + " redaction rule, and an auditor with one more place to look."),
                ArchitectureRule.optIn(NO_PRIVATE_AUDIT_LOGGER_NAMES, noPrivateAuditLoggerNames(),
                        "Do not create a logger whose name ends in '.audit'. The platform's trail is on the"
                                + " ru.ludwigandreas.audit logger, routed to its own appender and retention;"
                                + " a second audit logger name is a second stream nobody has configured."));
    }

    /**
     * No interface outside the audit module is named like an audit seam.
     *
     * <p>Interfaces only, and only the ones somebody could implement. A concrete class named
     * {@code SomethingAuditRecorder} is a caller of the sink - {@code SettingsAuditRecorder} is exactly
     * that after the consolidation - and forbidding the name would be forbidding a reasonable name for a
     * class that does the right thing. What must not exist is a second <em>seam</em>: an interface that
     * invites a deployment to publish its own implementation, because that is the point at which a
     * module's trail stops going where the platform's goes.
     */
    private static ArchRule noSecondAuditSpi() {
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(AUDIT_PACKAGES)))
                .and().areInterfaces()
                .should(beNamedLikeAnAuditSeam())
                .as("No module declares an audit SPI of its own");
    }

    /**
     * No class outside the audit module names a logger {@code *.audit}.
     *
     * <p>Opt-in, and the reason is worth stating: this one is a heuristic over string arguments to
     * {@code LoggerFactory.getLogger}, which ArchUnit cannot read - so it is implemented over the
     * <em>declaring class's own package and name</em> instead, catching the class that lives in an
     * {@code audit} package and declares a static logger field. That finds the shape the seven deleted
     * SLF4J implementations had and misses a logger name assembled at runtime, which is why it is not on
     * by default: a rule that catches most of a thing is useful to opt into and wrong to impose.
     */
    private static ArchRule noPrivateAuditLoggerNames() {
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(AUDIT_PACKAGES)))
                .and(ArchitecturePredicates.residingIn(List.of("..audit..")))
                .should(declareAStaticLoggerField())
                .as("No module keeps an audit logger of its own");
    }

    private static ArchCondition<JavaClass> beNamedLikeAnAuditSeam() {
        return new ArchCondition<>("be named like an audit sink, logger, emitter or recorder") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean matches = AUDIT_SEAM_NAME.matcher(item.getSimpleName()).matches()
                        && declaresARecordingMethod(item);
                events.add(new SimpleConditionEvent(item, matches,
                        item.getName() + (matches ? " is" : " is not") + " a second audit SPI"));
            }
        };
    }

    /**
     * Whether the interface has a method that looks like "take this event and write it down".
     *
     * <p>The name test alone would flag a marker interface or a query-side type that merely mentions
     * auditing. Requiring one void, single-argument method as well is what narrows it to the shape all
     * nine had.
     */
    private static boolean declaresARecordingMethod(JavaClass item) {
        for (JavaMethod method : item.getMethods()) {
            boolean recording = method.getRawReturnType().getName().equals("void")
                    && method.getRawParameterTypes().size() >= 1
                    && !method.getModifiers().contains(JavaModifier.STATIC);
            if (recording) {
                return true;
            }
        }
        return false;
    }

    private static ArchCondition<JavaClass> declareAStaticLoggerField() {
        DescribedPredicate<JavaClass> loggerType = DescribedPredicate.describe("an SLF4J logger",
                javaClass -> javaClass.getName().toLowerCase(Locale.ROOT).endsWith(".logger"));
        return new ArchCondition<>("declare a static logger field of its own") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean declares = item.getFields().stream()
                        .anyMatch(field -> field.getModifiers().contains(JavaModifier.STATIC)
                                && loggerType.test(field.getRawType()));
                events.add(new SimpleConditionEvent(item, declares,
                        item.getName() + (declares ? " declares" : " does not declare")
                                + " an audit logger of its own"));
            }
        };
    }
}
