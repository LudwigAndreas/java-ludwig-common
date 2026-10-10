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
 * There is one log pipeline and one statement of what a build was made from, and neither is restated.
 *
 * <p>Three rules. {@code observability-spring-boot-starter} shapes every line a service writes - the field
 * names, the identity on each event, the commit the artifact was built from - and resolves that commit from
 * resources packaged at build time. The ways that goes wrong are a second module shaping log output, a
 * second type saying what the build was, and a process asking the repository at runtime instead of reading
 * what was packaged.
 *
 * <h2>A second encoder is not a service's own logging configuration</h2>
 *
 * <p>This is the distinction the first rule turns on, and getting it wrong in either direction breaks
 * something. A service that writes a {@code logback-spring.xml} and attaches an appender is <em>choosing
 * its own output</em>: it affects that service's stream and nothing else, the observability starter
 * deliberately leaves such an appender exactly as it found it, and no rule here forbids it. A module that
 * declares a type <em>implementing</em> Logback's {@code Encoder} or {@code Layout} is doing something
 * different in kind: it is publishing a second field vocabulary that other modules can write through. Two
 * vocabularies are discovered to disagree only once both are deployed, at which point the aggregator's index
 * template already has the wrong mapping for one of them - the same defect as a second audit sink or a
 * second operation status enum, in the one place where nothing fails and the lines simply stop being
 * findable.
 *
 * <p>So the rule is about a <em>type</em>, which is a bytecode fact, and not about a configuration file,
 * which is a resource ArchUnit cannot see and which this group has no business forbidding.
 *
 * <h2>What this checks, and what it deliberately does not</h2>
 *
 * <p><b>Checked here:</b> a type implementing a Logback encoder or layout; a type whose whole content is
 * build provenance; and the two ways a JVM can reach a git repository that leave a trace in bytecode -
 * launching an operating-system process, and depending on JGit.
 *
 * <p><b>Not checked here: which program a launched process runs.</b> {@code new ProcessBuilder("git", ...)}
 * and {@code new ProcessBuilder("pg_dump", ...)} are the same bytecode with a different string constant, and
 * ArchUnit cannot read a string constant's value - the limit {@link AuditRules} records about the redaction
 * mask. The third rule therefore reports <em>every</em> process launch outside {@link #PROCESS_EXEMPT_TYPES},
 * which is broader than "no git process" and is the only form of that rule a build can enforce. Nothing in
 * the platform launches a process today, so the list is empty; a module with a real reason to launch one
 * adds its type there, with the reason, where the next reader sees it.
 *
 * <p><b>Also not checked: opening a {@code .git} path through {@code java.io} or {@code java.nio}.</b> The
 * path is a string constant too. It is not handed to Checkstyle either, on purpose: a source-text rule
 * matching {@code .git} would fire on {@code .gitignore}, on every repository URL, and on the javadoc of
 * the very class that explains why not to read it. That one stays a review question, and it is recorded as
 * a requirement of the {@code build-identity} capability so a review has something to point at.
 */
public final class LoggingRules implements ArchitectureRuleSet {

    /** Nothing outside the observability module implements a Logback encoder or layout. */
    public static final RuleId NO_SECOND_LOG_ENCODER =
            RuleId.of(RuleGroup.LOGGING, "no-second-log-encoder");

    /** Nothing declares a second type holding build provenance. */
    public static final RuleId NO_SECOND_BUILD_PROVENANCE_TYPE =
            RuleId.of(RuleGroup.LOGGING, "no-second-build-provenance-type");

    /** Nothing reaches a git repository from a running process. */
    public static final RuleId NO_RUNTIME_REPOSITORY_ACCESS =
            RuleId.of(RuleGroup.LOGGING, "no-runtime-repository-access");

    /** The module that legitimately owns the log pipeline and the build identity. */
    private static final String OBSERVABILITY_PACKAGES = "ru.ludwigandreas.observability..";

    /**
     * Logback's two output-shaping contracts, by name so that this library needs no Logback dependency.
     *
     * <p>Both, because they are the same mistake at two levels: a {@code Layout} turns an event into text and
     * an {@code Encoder} turns it into bytes, and a rule that named only one would be satisfied by writing
     * the other.
     */
    private static final List<String> LOG_SHAPING_TYPES = List.of(
            "ch.qos.logback.core.encoder.Encoder",
            "ch.qos.logback.core.Layout");

    /** Field names that say which commit a build came from, normalised: lower case, no underscores. */
    private static final Set<String> COMMIT_FIELD_NAMES = Set.of(
            "commit", "commitid", "commithash", "commitsha", "gitcommit", "gitcommitid", "abbreviatedcommitid",
            "shortcommitid", "commitidabbrev", "revision", "gitrevision", "sha", "gitsha");

    /** Field names for the rest of a build's provenance, normalised the same way. */
    private static final Set<String> BUILD_FIELD_NAMES = Set.of(
            "branch", "gitbranch", "dirty", "gitdirty", "buildtime", "buildtimestamp", "builtat",
            "buildnumber", "cibuildnumber", "buildid");

    private static final String PROCESS_BUILDER = "java.lang.ProcessBuilder";

    /** JGit, the way a JVM reads a {@code .git} directory without launching anything. */
    private static final String JGIT_PACKAGE = "org.eclipse.jgit";

    /**
     * The types that may launch an operating-system process, and why each one may.
     *
     * <p>Empty, and named here rather than left to a suppression at the call site for the reason
     * {@link CachingRules} gives about its own list: the set of exceptions should be one list somebody can
     * read. An entry is a claim that the process launched is not {@code git} - which is exactly the part of
     * this rule a build cannot check, so the claim has to be made where it is reviewed.
     */
    private static final List<String> PROCESS_EXEMPT_TYPES = List.of();

    @Override
    public RuleGroup group() {
        return RuleGroup.LOGGING;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(
                ArchitectureRule.of(NO_SECOND_LOG_ENCODER, noSecondLogEncoder(),
                        "Do not implement a Logback Encoder or Layout outside"
                                + " observability-spring-boot-starter. That module's encoder is the one place"
                                + " the platform's log field names are decided; a second implementation is a"
                                + " second field vocabulary, found to disagree with the first only after the"
                                + " aggregator's index template has the wrong mapping for one of them. To"
                                + " change what a line carries, change it there. To choose a different output"
                                + " for ONE service, configure an appender in that service's own"
                                + " logback-spring.xml - the starter leaves it alone, and this rule does not"
                                + " forbid it."),
                ArchitectureRule.of(NO_SECOND_BUILD_PROVENANCE_TYPE, noSecondBuildProvenanceType(),
                        "Use ru.ludwigandreas.observability.core.BuildIdentity instead of declaring a second"
                                + " type whose whole content is a commit, a branch and a build time. One"
                                + " record means one resolution of what this process was built from; a second"
                                + " one is resolved separately and reports a different commit on the day it"
                                + " matters. A type that carries a commit ALONGSIDE ITS OWN STATE - a release"
                                + " note, a deployment record about some other service - is a domain object"
                                + " and is not reported."),
                ArchitectureRule.of(NO_RUNTIME_REPOSITORY_ACCESS, noRuntimeRepositoryAccess(),
                        "Read build provenance from BuildIdentity, which comes from the git.properties and"
                                + " build-info.properties packaged into the artifact - never by running git"
                                + " or opening a repository. A container has no checkout, and a process that"
                                + " found one would report the machine it runs on instead of the build it"
                                + " came from. This rule cannot see WHICH program a process runs, so it"
                                + " reports every process launch: if the process is genuinely not git, add"
                                + " the launching type to LoggingRules.PROCESS_EXEMPT_TYPES with the reason."));
    }

    /**
     * No type outside the observability module is assignable to a Logback encoder or layout.
     *
     * <p>Assignable, not "directly implements", so that extending {@code EncoderBase},
     * {@code LayoutWrappingEncoder} or {@code PatternLayout} is caught too - those are the convenient ways to
     * write one, and therefore the likely ones.
     */
    private static ArchRule noSecondLogEncoder() {
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(OBSERVABILITY_PACKAGES)))
                .should(shapeLogOutput())
                .as("Only the observability module (" + OBSERVABILITY_PACKAGES + ") implements a log encoder"
                        + " or layout");
    }

    /**
     * No type outside the observability module <em>is</em> build provenance.
     *
     * <p>The discriminator is the one {@link PresentationRules} arrived at for the caller-preference pair,
     * for the same reason: every declared instance field is a provenance field, at least one names the commit
     * and at least one names something else about the build, and there is <b>nothing else</b>. Asking only
     * that a commit and a branch be present would report every domain type that legitimately records them -
     * a deployment log, a release note, a change request - each of which carries the pair beside state of its
     * own and describes something other than the running process.
     *
     * <p><b>The limit, which cannot be checked:</b> one unrelated field evades this rule, and so does a
     * field name outside the two lists. Accepted, as it is there: the rule exists to catch the reasonable
     * local decision - "I need the commit here, I will make a small record" - and not a determined evasion.
     */
    private static ArchRule noSecondBuildProvenanceType() {
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(OBSERVABILITY_PACKAGES)))
                .should(beNothingButBuildProvenance())
                .as("No module outside " + OBSERVABILITY_PACKAGES + " declares a second build-provenance type");
    }

    /**
     * Nothing launches a process or depends on JGit.
     *
     * <p>{@code classes().should(not...)} and not {@code noClasses()}: both conditions here report only
     * violations, and {@code noClasses()} inverts every event, which turns such a condition into one that
     * reports nothing. See {@link PresentationRules} for how that was found.
     *
     * <p>{@code Runtime.exec} is listed beside {@code ProcessBuilder} because they are the same act through
     * two APIs. The rest of {@code Runtime} is not: {@code availableProcessors()} and {@code maxMemory()}
     * are ordinary and are in use across the platform.
     */
    private static ArchRule noRuntimeRepositoryAccess() {
        DescribedPredicate<JavaClass> repositoryAccess = DescribedPredicate.describe(
                "ProcessBuilder or JGit",
                javaClass -> PROCESS_BUILDER.equals(javaClass.getName())
                        || javaClass.getPackageName().equals(JGIT_PACKAGE)
                        || javaClass.getPackageName().startsWith(JGIT_PACKAGE + "."));
        return ArchRuleDefinition.classes()
                .that(DescribedPredicate.not(processExempt()))
                .should(ArchitectureConditions.notDependOnClassesThat(repositoryAccess,
                                "an operating-system process or a git repository")
                        .and(ArchitectureConditions.notCallMethods(Map.of("java.lang.Runtime", Set.of("exec")),
                                "Runtime.exec"))
                        .as("not launch a process or open a git repository"))
                .as("Build provenance is read from the packaged artifact, never from a repository at runtime");
    }

    private static DescribedPredicate<JavaClass> processExempt() {
        return DescribedPredicate.describe("types exempted by name - see PROCESS_EXEMPT_TYPES",
                javaClass -> PROCESS_EXEMPT_TYPES.contains(javaClass.getName()));
    }

    private static ArchCondition<JavaClass> shapeLogOutput() {
        return new ArchCondition<>("implement a Logback Encoder or Layout") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean matches = LOG_SHAPING_TYPES.stream().anyMatch(item::isAssignableTo);
                events.add(new SimpleConditionEvent(item, matches,
                        item.getName() + (matches ? " is" : " is not") + " a second log encoder or layout"));
            }
        };
    }

    private static ArchCondition<JavaClass> beNothingButBuildProvenance() {
        return new ArchCondition<>("be nothing but build provenance") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean commit = false;
                boolean build = false;
                int other = 0;
                for (JavaField field : item.getFields()) {
                    if (field.getModifiers().contains(JavaModifier.STATIC)) {
                        // A constant is not state; a record declaring its own EMPTY instance is judged on
                        // its components.
                        continue;
                    }
                    String name = field.getName().toLowerCase(Locale.ROOT).replace("_", "");
                    if (COMMIT_FIELD_NAMES.contains(name)) {
                        commit = true;
                    } else if (BUILD_FIELD_NAMES.contains(name)) {
                        build = true;
                    } else {
                        other++;
                    }
                }
                boolean matches = commit && build && other == 0;
                events.add(new SimpleConditionEvent(item, matches,
                        item.getName() + (matches ? " is" : " is not")
                                + " a second type whose whole content is build provenance"));
            }
        };
    }
}
