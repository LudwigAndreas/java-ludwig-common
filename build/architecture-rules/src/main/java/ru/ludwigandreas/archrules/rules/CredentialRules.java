package ru.ludwigandreas.archrules.rules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;

/**
 * There is one way a long-lived credential becomes authority, and nobody writes a second one.
 *
 * <p>Unlike {@link AuditRules} and {@link CachingRules}, this rule set is not the durable half of a
 * consolidation - there was nothing to consolidate. It is written <em>before</em> the code it governs,
 * deliberately, because the invariant it protects is one that cannot be restored after it has been broken.
 *
 * <h2>The invariant</h2>
 *
 * <p>A personal access token is an <b>attenuation</b> of its owner's live authority: the effective authority
 * of a request it backs is the intersection of what the owner holds <em>now</em> and what the token's scopes
 * name. It is never a union, and never a snapshot taken at issuance.
 *
 * <p>That is not a stylistic preference. {@code LudwigPrincipal} already states the platform's position -
 * the identity provider issues identity, not entitlement, so that a revoked role stops working within a cache
 * TTL rather than a token lifetime, and so that a stolen token cannot carry elevated roles that were never
 * granted. A token that froze its owner's roles at issuance would be exactly the token-carried role that
 * reasoning refuses, with a ninety-day lifetime attached.
 *
 * <p>Prose cannot hold that. The intersection is one line, the union is also one line, and the difference
 * between them is invisible in review and invisible in every test that happens to use a principal whose
 * authority exceeds the token's scopes. What holds it is {@link #ONE_ATTENUATION_PATH}: if exactly one place
 * in the platform can construct a credential-backed principal, and that place intersects, then no second
 * place can get it wrong.
 *
 * <h2>What this checks, and what it deliberately does not</h2>
 *
 * <p><b>Checked here:</b> that no module outside the credential modules stores or mints credential material,
 * that no module declares a second seam for supplying it, that the raw secret is readable only from the two
 * packages that have to read it, and that the construction of a credential-backed principal happens in one
 * place. All four are structure - a dependency, a type's shape, a method call and a constructor call - which
 * is what ArchUnit sees.
 *
 * <p><b>Not checked here: that the one construction path actually intersects rather than unions.</b> That is
 * a statement about what a method body computes, and no structural rule reaches it. It is covered where it
 * can be - by unit tests over the converter that assert a demoted owner loses the authority and that no union
 * is reachable - and the rule above is what makes those tests sufficient rather than merely indicative,
 * because it guarantees there is nothing else to test.
 *
 * <p><b>Not checked here: that a plain digest is the right choice.</b> The algorithm is a string literal
 * argument and bytecode analysis cannot see a string constant's value, which is the same reason the
 * second-redaction-mask rule lives in Checkstyle rather than here. The weak-algorithm check is Checkstyle's.
 *
 * <p><b>Not checked anywhere, and deliberately not written as a rule here: that a long-lived connection
 * re-derives authority on an interval.</b> Nothing in this repository holds a connection open, so this
 * rule's matching set would be empty: it would pass on the day it was written and keep passing after the
 * requirement had been broken in a module that does not exist yet. A rule that passes because there is
 * nothing to check is worse than an absent one - it appears in the enforcement table, it stays green
 * forever, and it gives the next reader a reason not to look. The enforcing test is a recorded obligation on
 * the change that introduces the first streaming transport; the configuration property and its startup
 * ceiling check ship ahead of it so the knob exists before the transport does.
 */
public final class CredentialRules implements ArchitectureRuleSet {

    /** Nothing outside the credential modules stores or mints credential material. */
    public static final RuleId NO_SECOND_CREDENTIAL_STORE =
            RuleId.of(RuleGroup.CREDENTIALS, "no-second-credential-store");

    /** Nothing outside the credential modules declares a seam for supplying credential material. */
    public static final RuleId NO_SECOND_CREDENTIAL_SPI =
            RuleId.of(RuleGroup.CREDENTIALS, "no-second-credential-spi");

    /** The raw secret is readable only where it has to be read. */
    public static final RuleId SECRET_REVEAL_IS_FENCED =
            RuleId.of(RuleGroup.CREDENTIALS, "secret-reveal-is-fenced");

    /** A credential-backed authentication is constructed in exactly one place. */
    public static final RuleId ONE_ATTENUATION_PATH =
            RuleId.of(RuleGroup.CREDENTIALS, "one-attenuation-path");

    /** The raw-secret carrier does not appear in a signature outside the credential modules. */
    public static final RuleId SECRET_CARRIER_STAYS_INSIDE =
            RuleId.of(RuleGroup.CREDENTIALS, "secret-carrier-stays-inside");

    /**
     * The packages that legitimately own credential material.
     *
     * <p>Two, not one, and the split is the whole module topology: {@code pat-core} holds the format and the
     * secret carrier and has zero in-repo dependencies, so that the security starter can read a token's claim
     * without acquiring a persistence dependency; the starter holds the store and the issuance service.
     */
    private static final List<String> CREDENTIAL_PACKAGES = List.of("ru.ludwigandreas.pat..");

    /**
     * The one package that may construct a credential-backed principal.
     *
     * <p>Narrower than the whole security starter on purpose. The converter package is where a token's claim
     * is turned into authority, and it is the only place that has both halves of the intersection in hand -
     * the owner's live authorities from the authority lookup, and the token's scopes from the claim.
     */
    private static final String ATTENUATION_PACKAGE = "ru.ludwigandreas.security.authn.jwt..";

    /** The secret carrier, named as a string so this jar does not depend on {@code pat-core}. */
    private static final String SECRET_CARRIER = "ru.ludwigandreas.pat.token.PatSecret";

    /**
     * The authentication whose construction with a credential is fenced.
     *
     * <p>The <b>authentication</b> and not the principal, and the distinction is the same one the design
     * turns on: a credential is a property of how one request authenticated, not of who the caller is. An
     * earlier version of this rule keyed on {@code LudwigPrincipal}, which would have fenced the wrong type
     * and - worse - would have passed vacuously once the credential moved, since no principal carries one.
     */
    private static final String AUTHENTICATION =
            "ru.ludwigandreas.security.principal.LudwigAuthentication";

    /**
     * The accessor that hands out the raw secret.
     *
     * <p>Fenced rather than absent, because the issuance response has to return the secret exactly once and
     * the exchange has to hash what was presented. Both of those are legitimate reads; a third one is not.
     */
    private static final String REVEAL = "reveal";

    /**
     * A type name that reads as a credential store.
     *
     * <p>Names rather than shapes, for the reason {@link CachingRules} gives about caches: the shape of a
     * credential store - find by id, save, delete - is the shape of every repository in the platform, and a
     * rule keyed on that would flag all of them. What makes a second credential store recognisable is that
     * somebody called it one.
     */
    private static final Pattern CREDENTIAL_STORE_NAME = Pattern.compile(
            "(?i).*(token|secret|credential|apikey|accesskey)(store|repository|registry|vault|table)$");

    /**
     * A type name that reads as a credential seam.
     *
     * <p>Deliberately does not match {@code *Minter} or {@code *Signer}. A deployment supplying its own
     * assertion minter is the supported extension point - the starter ships an interface precisely so an
     * existing identity provider can own the signing key - and forbidding that name would forbid the thing
     * the design asks for.
     */
    private static final Pattern CREDENTIAL_SEAM_NAME = Pattern.compile(
            "(?i).*(token|secret|credential|apikey)(provider|resolver|source|supplier|lookup)$");

    @Override
    public RuleGroup group() {
        return RuleGroup.CREDENTIALS;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(
                ArchitectureRule.of(NO_SECOND_CREDENTIAL_STORE, noSecondCredentialStore(),
                        "Keep credential material in the one store that governs it. A second store is a"
                                + " second revocation surface, and revocation that works in one of two"
                                + " places is the failure mode a personal access token exists to prevent:"
                                + " a leaked token gets revoked where somebody remembered to look, and"
                                + " keeps working everywhere else. It is also a second retention policy, a"
                                + " second digest choice and a second place a raw secret can be written by"
                                + " mistake."),
                ArchitectureRule.of(NO_SECOND_CREDENTIAL_SPI, noSecondCredentialSpi(),
                        "Use the credential module's own seam instead of declaring one here. A second seam"
                                + " is an invitation for a deployment to supply credential material that"
                                + " the platform's issuance rules, audit events and attenuation never see -"
                                + " at which point the attenuation invariant holds for one kind of"
                                + " credential and not the other, which is indistinguishable from it not"
                                + " holding at all."),
                ArchitectureRule.of(SECRET_REVEAL_IS_FENCED, secretRevealIsFenced(),
                        "Read the digest instead of the raw secret. The carrier masks itself in toString()"
                                + " and this rule fences the accessor that defeats that masking, because"
                                + " the two together are what keep a secret out of a log: once the raw"
                                + " String has escaped the carrier, no rule can follow it. Issuance"
                                + " returns the secret once and the exchange hashes what was presented;"
                                + " a third reader is a leak waiting for a log statement."),
                ArchitectureRule.of(ONE_ATTENUATION_PATH, oneAttenuationPath(),
                        "Let the credential converter build the principal. This is the rule that holds the"
                                + " attenuation invariant: a credential's effective authority is its"
                                + " owner's live authority INTERSECTED with the credential's scopes, never"
                                + " a union and never a snapshot. The intersection is one line and so is"
                                + " the union, the difference between them is invisible in review, and a"
                                + " second construction site is where it gets written the other way. One"
                                + " construction site means there is nothing else to get wrong."),
                ArchitectureRule.of(SECRET_CARRIER_STAYS_INSIDE, secretCarrierStaysInside(),
                        "Take the digest or the parsed token, not the raw-secret carrier. Containment is"
                                + " what keeps a secret out of a log: code that never holds the carrier"
                                + " cannot log it, cannot put it in an audit attribute and cannot"
                                + " serialize it, which disposes of all three without enumerating any of"
                                + " them. The phrasing 'never passed to a logger' is the one this rule"
                                + " deliberately does not use - SLF4J takes Object..., so the carrier is"
                                + " already widened by the time it reaches a logging call and there is no"
                                + " typed parameter left to match."));
    }

    /**
     * No class outside the credential modules is named like a credential store.
     *
     * <p>{@code noClasses()} is correct here and {@code classes()} would be wrong, which is the opposite of
     * the polarity {@link CachingRules#noPrivateCaffeineCache} needs. The difference is the condition:
     * {@code beNamedLikeACredentialStore} reports an event for every class it inspects, violated or not, so
     * ArchUnit's {@code never()} wrapper inverts it correctly. A condition that reports <em>only</em>
     * violations - which is what {@code notDependOnClassesThat} does - comes out inert under
     * {@code noClasses()}, and did, for as long as nobody measured it against a fixture. Both halves of this
     * rule set are measured against fixtures in {@code CredentialRulesTest} for exactly that reason.
     */
    private static ArchRule noSecondCredentialStore() {
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(CREDENTIAL_PACKAGES))
                .should(beNamedLike(CREDENTIAL_STORE_NAME, "a credential store"))
                .as("No module outside the credential modules stores credential material");
    }

    /**
     * No interface outside the credential modules is named like a credential seam.
     *
     * <p>Interfaces only, for the reason {@link CachingRules} gives: a concrete class with one of these names
     * is usually a caller or an adapter, and forbidding the name would forbid a reasonable name for a class
     * doing the right thing. What must not exist is a second <em>seam</em> - the point at which a module
     * invites a deployment to publish credential material of its own.
     */
    private static ArchRule noSecondCredentialSpi() {
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(CREDENTIAL_PACKAGES))
                .and().areInterfaces()
                .should(beNamedLike(CREDENTIAL_SEAM_NAME, "a credential seam"))
                .as("No module declares a credential seam of its own");
    }

    /**
     * Only the credential modules read the raw secret.
     *
     * <p>Expressed as a call to the accessor rather than as a dependency on the carrier, because the
     * dependency is legitimate and widespread by design: the carrier is a parameter and a return type across
     * the issuance path, and the whole point of having a type for it is that it can be passed around safely.
     * What must be rare is <em>unwrapping</em> it.
     */
    private static ArchRule secretRevealIsFenced() {
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingOutsideOf(CREDENTIAL_PACKAGES))
                .should(ArchitectureConditions.notCallMethods(
                                Map.of(SECRET_CARRIER, Set.of(REVEAL)),
                                "the raw-secret accessor")
                        .as("not read the raw secret"))
                .as("Only the credential modules read a raw secret");
    }

    /**
     * Only the converter package constructs a principal carrying a credential.
     *
     * <p>{@code LudwigAuthentication} is an ordinary class rather than a record, so there is exactly one
     * construction mechanism and no builder to reason about - which is simpler than the principal-based
     * version of this rule would have been. Every way of obtaining a credential-backed authentication goes
     * through the two-argument constructor, and this rule sees all of them.
     */
    private static ArchRule oneAttenuationPath() {
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingOutsideOf(
                        List.of(ATTENUATION_PACKAGE, "ru.ludwigandreas.pat..")))
                .should(notConstructCredentialBackedAuthentication())
                .as("A credential-backed authentication is constructed in exactly one place");
    }

    private static ArchCondition<JavaClass> beNamedLike(Pattern pattern, String description) {
        return new ArchCondition<>("be named like " + description) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean matches = pattern.matcher(item.getSimpleName()).matches();
                events.add(new SimpleConditionEvent(item, matches,
                        item.getName() + (matches ? " is" : " is not") + " named like " + description));
            }
        };
    }

    /**
     * Fails for a constructor call on the authentication from a class that also mentions the credential kind.
     *
     * <p>The two conditions together are what make this checkable without flagging every existing
     * authentication construction in the platform. Constructing one is ordinary and happens in the JWT
     * converter, the mTLS filter, the system-principal template and a great many tests. What is not ordinary
     * is doing it in a class that also carries the credential enum in its constant pool, which is the
     * signature of a second place deciding what a credential is worth.
     */
    private static ArchCondition<JavaClass> notConstructCredentialBackedAuthentication() {
        return new ArchCondition<>("not construct a credential-backed authentication") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (!mentionsCredentialKind(item)) {
                    return;
                }
                for (JavaConstructorCall call : item.getConstructorCallsFromSelf()) {
                    if (AUTHENTICATION.equals(call.getTargetOwner().getName())) {
                        events.add(SimpleConditionEvent.violated(item, call.getDescription()));
                    }
                }
            }
        };
    }

    private static boolean mentionsCredentialKind(JavaClass item) {
        for (JavaClass dependency : item.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass())
                .toList()) {
            if (dependency.getName().startsWith("ru.ludwigandreas.security.principal.CredentialKind")) {
                return true;
            }
        }
        return false;
    }

    /**
     * No method outside the credential modules has the secret carrier in its signature.
     *
     * <p>This is the containment rule, and it is deliberately <em>not</em> the rule the design first reached
     * for. "The carrier is never passed to a logger" is the obvious phrasing and it is not checkable: SLF4J
     * takes {@code Object...}, so by the time the carrier reaches a logging method it has been widened to
     * {@code Object} and there is no typed parameter left for a rule to match. Keying on the logging call
     * would catch the shape nobody writes and miss every shape somebody does.
     *
     * <p>Containment is checkable and strictly stronger. If no method outside the credential modules can
     * accept or return the carrier, then no code outside them holds one, and code that does not hold one
     * cannot log one - which disposes of the logging case, the audit-attribute case, and the cases nobody
     * thought of, without enumerating any of them.
     */
    private static ArchRule secretCarrierStaysInside() {
        DescribedPredicate<JavaClass> carrier = DescribedPredicate.describe(
                "the raw-secret carrier",
                javaClass -> SECRET_CARRIER.equals(javaClass.getName()));
        return ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat(ArchitecturePredicates.residingOutsideOf(CREDENTIAL_PACKAGES))
                .should(ArchitectureConditions.notHaveSignatureTypesThat(carrier, "a raw secret")
                        .as("not carry a raw secret in their signature"))
                .as("The raw-secret carrier does not leave the credential modules");
    }
}
