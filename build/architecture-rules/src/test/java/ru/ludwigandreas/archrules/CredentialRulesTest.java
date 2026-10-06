package ru.ludwigandreas.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.archrules.rules.CredentialRules;

/**
 * The credential rules, shown firing and shown staying silent in the same run.
 *
 * <p>Both halves matter and the second one matters more, for the reason {@link PresentationRulesTest} gives:
 * a rule that caught the second credential store by also catching every repository, every assertion minter
 * and every token holder would be switched off within a quarter, and the defect it existed to prevent then
 * comes back with the rule notionally still in place.
 *
 * <p>There is a second reason specific to this group. {@code CachingRules} carries a comment recording that
 * its Caffeine rule was <b>inert from the day it was written</b> - {@code noClasses()} wraps the condition in
 * ArchUnit's {@code never()}, which inverts every event, and a condition that reports only violations
 * therefore reports nothing at all under it. That was discovered by measuring a rule against a fixture, not
 * by reading it. Every rule in this set is measured here for that reason, including the ones that look
 * obviously correct.
 */
class CredentialRulesTest {

    @Test
    @DisplayName("a second credential store is reported; a repository, a minter and a token holder are not")
    void secondCredentialStoreIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.credentials").build()))
                .contains(CredentialRules.NO_SECOND_CREDENTIAL_STORE.value());

        assertThat(Fixtures.violationDescriptions(
                Fixtures.configurationFor("bad.credentials").build(),
                CredentialRules.NO_SECOND_CREDENTIAL_STORE))
                .anyMatch(description -> description.contains("ApiKeyStore"))
                .noneMatch(description -> description.contains("OrderRepository"))
                .noneMatch(description -> description.contains("PatAssertionMinter"))
                .noneMatch(description -> description.contains("CachedToken"));
    }

    @Test
    @DisplayName("a second credential seam is reported; the supported assertion minter is not")
    void secondCredentialSpiIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.credentials").build()))
                .contains(CredentialRules.NO_SECOND_CREDENTIAL_SPI.value());

        // PatAssertionMinter is the assertion that matters here. It is an interface, it is about credentials,
        // and it is the extension point the design deliberately ships - so a seam pattern wide enough to
        // catch it would forbid the thing the module exists to offer.
        assertThat(Fixtures.violationDescriptions(
                Fixtures.configurationFor("bad.credentials").build(),
                CredentialRules.NO_SECOND_CREDENTIAL_SPI))
                .anyMatch(description -> description.contains("TokenSecretProvider"))
                .noneMatch(description -> description.contains("PatAssertionMinter"));
    }

    @Test
    @DisplayName("a module with no credential of its own breaks none of the five rules")
    void compliantModuleIsSilent() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("good").build()))
                .doesNotContain(
                        CredentialRules.NO_SECOND_CREDENTIAL_STORE.value(),
                        CredentialRules.NO_SECOND_CREDENTIAL_SPI.value(),
                        CredentialRules.SECRET_REVEAL_IS_FENCED.value(),
                        CredentialRules.ONE_ATTENUATION_PATH.value(),
                        CredentialRules.SECRET_CARRIER_STAYS_INSIDE.value());
    }

    @Test
    @DisplayName("the rule names one type, so a second caller cannot satisfy it by being added")
    void theAttenuationFenceNamesOneType() {
        // Asserted against the rule's own description rather than against a fixture, because the subject
        // is which TYPE the fence names and that is not something a synthetic service can demonstrate.
        //
        // It matters because of how this rule was nearly weakened. When a second authentication path was
        // added there were two options: append that path's package to the fence (widening it - two
        // packages today, three next year) or extract the construction into one type and name that
        // (narrowing it). The second was taken, and this assertion is what would fail if somebody
        // reverted to a package fence while keeping every other test green.
        assertThat(Fixtures.ruleDescription(
                Fixtures.configurationFor("good").build(), CredentialRules.ONE_ATTENUATION_PATH))
                .contains("exactly one place");
    }

    /**
     * The three rules whose subjects do not exist in any fixture, shown to be vacuous rather than broken.
     *
     * <p>This is the uncomfortable assertion and it is the honest one. The reveal fence, the attenuation path
     * and the carrier containment all key on types in {@code pat-core} and
     * {@code security-spring-boot-starter}, which the fixtures do not and should not depend on - the fixtures
     * are a synthetic service, and giving them a real dependency on the platform's security module to
     * exercise a rule would make every other rule in this jar harder to reason about.
     *
     * <p>So what this test records is a limit: these three rules are proven not to <em>misfire</em>, and are
     * proven to <em>fire</em> only by the modules that actually contain their subjects - {@code pat-core}'s
     * and the security starter's own {@code ArchitectureRulesTest} runs, where the types are real. A rule
     * whose fixture cannot exist is not the same as a rule nobody checked, but it is not as good as one with
     * a fixture either, and saying so here is better than leaving the gap for a reader to find.
     */
    @Test
    @DisplayName("the three type-dependent rules are vacuous against the fixtures, not silently inverted")
    void typeDependentRulesAreVacuousAgainstFixtures() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.credentials").build()))
                .doesNotContain(
                        CredentialRules.SECRET_REVEAL_IS_FENCED.value(),
                        CredentialRules.ONE_ATTENUATION_PATH.value(),
                        CredentialRules.SECRET_CARRIER_STAYS_INSIDE.value());
    }
}
