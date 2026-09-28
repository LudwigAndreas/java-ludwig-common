package ru.ludwigandreas.archrules.rules;

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
import java.util.Set;
import java.util.stream.Collectors;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;

/**
 * There is one vocabulary for long-running operations, and nobody declares a second one.
 *
 * <h2>Why this rule is the deliverable</h2>
 *
 * <p>Five modules of this platform had each named the states of a long-running run for themselves,
 * and they disagreed: export said {@code SUCCEEDED}, file-ingest said {@code COMPLETED}, for one
 * state, in one platform, in two APIs. Every one of those was a reasonable local decision - a module
 * needs to record where its runs are, an enum is the idiomatic answer, and nobody was in a position
 * to see the other four. Consolidating them onto
 * {@code ru.ludwigandreas.webcore.operation.OperationStatus} fixes the state of the code; only a
 * rule stops the same reasonable local decision producing a sixth vocabulary next quarter.
 *
 * <h2>What counts as a second vocabulary, precisely</h2>
 *
 * <p>The discriminator is deliberately narrow, because the shape being looked for - an enum with a
 * success constant and a failure constant - is also the shape of half the internal work records in
 * the platform, and a rule that flagged those would be wrong far more often than it was right. An
 * enum is a second <em>operation vocabulary</em> when all three hold:
 *
 * <ol>
 *   <li><b>Every one of its constants is a word from the shared vocabulary or a synonym of one.</b>
 *       This is what separates a competing vocabulary from a domain lifecycle. Reconciliation's
 *       {@code RemoteJobState} has {@code SUCCEEDED} and {@code FAILED} in it, and it is not a
 *       violation: {@code PENDING_SUBMIT}, {@code COLLECTING}, {@code COLLECTED} and
 *       {@code ORPHANED} carry information the common core cannot, so it is a richer lifecycle that
 *       <em>maps onto</em> the vocabulary rather than a restatement of it. The same goes for
 *       notification's {@code RequestStatus} and its {@code FANNED_OUT}.</li>
 *   <li><b>It spans the whole life</b> - a not-yet-finished constant, a success and a failure.</li>
 *   <li><b>It models a client-visible stop or expiry</b> - {@code CANCELLED}, {@code EXPIRED},
 *       {@code ABORTED}, {@code TIMED_OUT}. This is the clause that separates an operation a client
 *       watches from an internal work record. {@code ClaimState} in the idempotency starter is
 *       {@code IN_PROGRESS}/{@code COMPLETED}/{@code FAILED}, which satisfies the first two clauses
 *       and is not an operation vocabulary at all - it is the state of a claim on a key, nobody
 *       cancels it and it is never published. Without this clause the rule would flag it, and a rule
 *       that has to be suppressed in the module that introduced it is a rule nobody keeps.</li>
 * </ol>
 *
 * <p>The cost of that narrowness is stated rather than hidden: a three-state re-invention
 * ({@code RUNNING}/{@code COMPLETED}/{@code FAILED}, which is exactly what file-ingest had) is not
 * caught, because that shape cannot be told apart from an internal work record by looking at it.
 * What is caught is the full re-statement, which is the expensive kind - it is the one that reaches
 * an API, and it is the one that took a Liquibase changeset to undo.
 *
 * <h2>What this deliberately leaves to other tools</h2>
 *
 * <p>Nothing here checks that a module's <em>HTTP</em> responses follow the contract - that a 202
 * carries a {@code Location}, that a poll carries {@code Retry-After}. That is behaviour rather than
 * structure, ArchUnit sees neither, and it is enforced where it can be enforced exactly:
 * {@code OperationResponses} refuses to build the malformed response at all. Same division of labour
 * as everywhere else in this repository - {@code architecture-rules} owns structure and
 * dependencies, {@code checkstyle-rules} owns source text, SonarQube owns bugs and security.
 */
public final class OperationVocabularyRules implements ArchitectureRuleSet {

    /** No module outside {@code web-core} restates the platform's operation vocabulary. */
    public static final RuleId NO_SECOND_OPERATION_VOCABULARY =
            RuleId.of(RuleGroup.OPERATIONS, "no-second-operation-vocabulary");

    /** The package that legitimately declares the platform's operation types. */
    private static final String OPERATION_PACKAGES = "ru.ludwigandreas.webcore.operation..";

    /** Words for "accepted but not finished". */
    private static final Set<String> NON_TERMINAL = Set.of(
            "PENDING", "RUNNING", "QUEUED", "IN_PROGRESS", "INPROGRESS", "STARTED", "ACCEPTED",
            "WAITING", "SCHEDULED", "NEW", "CREATED", "SUBMITTED", "ACTIVE", "PROCESSING");

    /** Words for "finished, and it worked". */
    private static final Set<String> SUCCESS = Set.of(
            "SUCCEEDED", "SUCCESS", "SUCCESSFUL", "COMPLETED", "COMPLETE", "DONE", "FINISHED", "OK");

    /** Words for "finished, and it did not work". */
    private static final Set<String> FAILURE = Set.of(
            "FAILED", "FAILURE", "ERROR", "ERRORED", "REJECTED");

    /** Words for "finished because somebody or something stopped it". */
    private static final Set<String> STOPPED = Set.of(
            "CANCELLED", "CANCELED", "EXPIRED", "ABORTED", "TIMED_OUT", "TIMEDOUT", "TIMEOUT",
            "STOPPED", "INTERRUPTED");

    @Override
    public RuleGroup group() {
        return RuleGroup.OPERATIONS;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(ArchitectureRule.of(NO_SECOND_OPERATION_VOCABULARY, noSecondVocabulary(),
                "Use ru.ludwigandreas.webcore.operation.OperationStatus instead of restating the"
                        + " platform's operation vocabulary. If your lifecycle carries states the"
                        + " common core cannot express - a partner's COLLECTING, a submit that may or"
                        + " may not have been accepted - keep your enum: it is a richer lifecycle and"
                        + " it maps onto the shared one through OperationResponse.detail(), which is"
                        + " what that field is for. What must not exist is a second spelling of the"
                        + " same six states, because that is how one platform comes to call one state"
                        + " SUCCEEDED in one API and COMPLETED in another."));
    }

    private static ArchRule noSecondVocabulary() {
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(OPERATION_PACKAGES)))
                .and().areEnums()
                .should(restateTheOperationVocabulary())
                .as("No module declares an operation status vocabulary of its own");
    }

    private static ArchCondition<JavaClass> restateTheOperationVocabulary() {
        return new ArchCondition<>("restate the platform's operation vocabulary") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean matches = isSecondVocabulary(constantsOf(item));
                events.add(new SimpleConditionEvent(item, matches,
                        item.getName() + (matches ? " is" : " is not")
                                + " a second operation vocabulary"));
            }
        };
    }

    /**
     * The enum's own constants.
     *
     * <p>An enum constant compiles to a static field of the enum's own type, which is what
     * distinguishes it from the other static fields an enum may declare - the {@code Set} of
     * non-terminal states on {@code RemoteJobState}, for instance, which is a field and not a state.
     */
    private static Set<String> constantsOf(JavaClass item) {
        return item.getFields().stream()
                .filter(field -> field.getModifiers().contains(JavaModifier.STATIC))
                .filter(field -> field.getRawType().equals(item))
                .map(JavaField::getName)
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isSecondVocabulary(Set<String> constants) {
        if (constants.isEmpty()) {
            return false;
        }
        boolean allShared = constants.stream().allMatch(OperationVocabularyRules::isSharedWord);
        return allShared
                && constants.stream().anyMatch(NON_TERMINAL::contains)
                && constants.stream().anyMatch(SUCCESS::contains)
                && constants.stream().anyMatch(FAILURE::contains)
                && constants.stream().anyMatch(STOPPED::contains);
    }

    private static boolean isSharedWord(String constant) {
        return NON_TERMINAL.contains(constant) || SUCCESS.contains(constant)
                || FAILURE.contains(constant) || STOPPED.contains(constant);
    }
}
