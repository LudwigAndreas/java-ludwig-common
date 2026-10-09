package ru.ludwigandreas.notification.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.service.model.Audience;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.odatafilter.annotation.Filterable;

/**
 * The rules that keep the in-app carve-out inside its boundary.
 *
 * <h2>Why these live here and not in {@code architecture-rules}</h2>
 *
 * <p>{@code architecture-rules} is a shared, test-scoped jar: a {@code RuleGroup} added there is a
 * rule every consuming service inherits. Each rule below is about a channel vocabulary, a delivery
 * entity and a preference evaluator that exist in exactly one module, so in every other service they
 * would pass with nothing to check - and a rule that passes vacuously is worse than an absent one,
 * because it appears in the report and gives the next reader a reason not to look. A rule that can
 * only ever fire in one module belongs in that module, which is the precedent the two
 * {@code SqlConfinementTest} carve-outs already set.
 *
 * <p>These are ArchUnit rules because each is a fact about structure that is visible in bytecode -
 * a field access, a field name, a dependency, an annotation. The two rules this change needs that
 * are <em>not</em> visible there are enforced by tests instead and say so at their own site:
 * {@code InAppSettlementIT#noTransportSupportsInApp} (support is a predicate's return value) and
 * {@code InAppSettlementIT#claimIgnoresInAppDeliveries} (a query's result set).
 */
class InAppCarveOutRulesTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ru.ludwigandreas.notification");
    }

    /**
     * The rule that stops one declaration decaying back into the special cases it replaced.
     *
     * <p>Three separate rules in the dispatch path - quiet hours, digest collapsing and the address
     * suppression list - are each justified by the premise that delivery interrupts somebody. That
     * premise is false for a passive channel, and {@link ChannelType#isPassive()} is the one place
     * the exception is expressed. Writing {@code channel == IN_APP} in the preference path instead
     * works exactly once; by the third rule it is three copies of a decision that then disagree the
     * day a second passive transport is added.
     */
    @Test
    @DisplayName("the preference path consults the channel's class, never the IN_APP constant")
    void preferencePathDoesNotNameThePassiveConstant() {
        noClasses()
                .that().resideInAPackage("..service.preference..")
                .should().accessField(ChannelType.class, ChannelType.IN_APP.name())
                .because("a dispatch-eligibility rule is about interruption, not about a particular "
                        + "transport: it must ask ChannelType.isPassive() so that a second passive "
                        + "channel needs no edit here, and so the exception stays in one place")
                .check(productionClasses);
    }

    /**
     * Read state belongs to the inbox item, which is a document its recipient mutates, and must never
     * appear on the delivery, which is a work-queue row this service claims and updates continuously
     * and deliberately keeps narrow.
     *
     * <p>Putting it there is the obvious shortcut and it is wrong in three ways at once: one table
     * would be both a hot claim-query row and a user-owned document, the retention purge would have to
     * give one answer to two different questions - the delivery's window runs from creation and the
     * inbox's from being read - and the delivery is deliberately not an {@code AuditedEntity}, so a
     * recipient's own write to it would not be audited the way a user-owned mutation should be.
     */
    @Test
    @DisplayName("no read-state field is declared on a delivery entity")
    void deliveryEntityCarriesNoReadState() {
        noFields()
                .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("DeliveryEntity")
                .should().haveNameMatching("(seenAt|readAt|dismissedAt)")
                .because("a delivery is a work-queue row, not a document its recipient mutates: read "
                        + "state belongs on InboxItemEntity, whose retention window is anchored on "
                        + "being read rather than on age")
                .check(productionClasses);
    }

    /**
     * The in-app settlement must not write into {@code notification_delivery_content}.
     *
     * <p>That table is purged at {@code retention.content-ttl} - seven days by default - with a
     * startup check that a body never outlives the delivery that owns it. An unread inbox item has to
     * survive somebody going on holiday, so a body written there would be deleted out from under its
     * owner on day eight. The tables look identical and their retention windows do not, which is the
     * entire reason there are two of them, and nothing in the javadoc would stop the next reader
     * reusing the one that already existed.
     */
    @Test
    @DisplayName("the in-app settlement never touches the delivery content table")
    void settlementDoesNotReachDeliveryContent() {
        noClasses()
                .that().resideInAPackage("..service.inbox..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("DeliveryContentEntity")
                .orShould().dependOnClassesThat()
                .haveSimpleNameEndingWith("DeliveryContentRepository")
                .because("notification_delivery_content is purged at content-ttl (7d) with a startup "
                        + "check that a body never outlives its delivery, so an unread inbox body "
                        + "stored there would be deleted before its owner had read it")
                .check(productionClasses);
    }

    // ---------------------------------------------------------------------------------------------
    // Announcements. Same placement argument as everything above: an announcement aggregate and an
    // audience predicate exist in exactly one module.
    // ---------------------------------------------------------------------------------------------

    /**
     * The property the whole announcement aggregate rests on: one row, whatever the audience size.
     *
     * <p>A materialized audience is the obvious optimisation and it is wrong four ways at once - it
     * reintroduces the N rows the aggregate exists to avoid, it is stale the moment a role is
     * revoked, it makes a user created tomorrow invisible to an {@code EVERYONE} announcement, and it
     * turns retention from a constant-cost deletion back into a sweep. None of those is obvious from
     * reading the field that would cause them.
     */
    @Test
    @DisplayName("the announcement carries no materialized audience")
    void announcementHasNoMaterializedAudience() {
        noFields()
                .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("AnnouncementEntity")
                .should().haveRawType(java.util.Collection.class)
                .orShould().haveRawType(java.util.Set.class)
                .orShould().haveRawType(java.util.List.class)
                .because("an announcement is ONE row whatever its audience: a collection of subjects "
                        + "here reintroduces the per-recipient cost, goes stale on revocation, hides "
                        + "the announcement from users created later, and turns retention back into "
                        + "a sweep")
                .check(productionClasses);
    }

    /**
     * The allowlist is only a control if there is one way past it.
     *
     * <p>A second construction path would behave perfectly for every role on the list - every test
     * green, every valid publish fine - and would also accept every role that is not on it. Nothing
     * would look wrong until somebody announced to a role nobody meant to be addressable, which is
     * the definition of a check that needs to be structural rather than remembered.
     */
    @Test
    @DisplayName("only the audience resolver constructs a role-targeted audience")
    void onlyTheResolverBuildsARoleAudience() {
        noClasses()
                .that().resideOutsideOfPackage("..service.announcement..")
                .and().resideInAPackage("ru.ludwigandreas.notification..")
                .should().callMethod(Audience.class, "ofRole", String.class)
                .because("targetable-roles is consulted in exactly one place; a second construction "
                        + "path would accept every role that is not on the allowlist while looking "
                        + "entirely correct for every role that is")
                .check(productionClasses);
    }

    /**
     * Filtering by audience would let a caller enumerate which roles the platform addresses, and
     * therefore which roles exist - the same enumeration concern the resolver's indistinguishable
     * refusals exist to prevent, arriving by a different door.
     */
    @Test
    @DisplayName("the announcement audience is not filterable")
    void announcementAudienceIsNotFilterable() {
        noFields()
                .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("AnnouncementEntity")
                .and().haveNameMatching("audience.*")
                .should().beAnnotatedWith(Filterable.class)
                .because("filtering by audience enumerates which roles the platform addresses")
                .check(productionClasses);
    }

    /**
     * The inbox keeps its boring, obviously-correct predicate.
     *
     * <p>The inbox read path is safe because of {@code owner = me}. Announcement visibility is
     * derived from role membership, and merging the two would put a derived security term on the
     * endpoint every client polls. The feeds are separate precisely so that cannot happen by
     * accident, and this is the rule that keeps them separate.
     */
    @Test
    @DisplayName("the inbox read path never depends on an announcement")
    void inboxDoesNotDependOnAnnouncements() {
        noClasses()
                .that().resideInAPackage("..service.inbox..")
                .should().dependOnClassesThat().haveSimpleNameStartingWith("Announcement")
                .because("the inbox's correctness rests on a single owner predicate; an announcement's "
                        + "visibility is derived, and the two must not be mixed on the service's "
                        + "most-polled endpoint")
                .check(productionClasses);
    }

    /**
     * A filterable owner is an existence oracle: it answers "has the platform told this person about
     * this?" for any subject a caller cares to name. Stricter than the delivery's own
     * {@code recipientUserId}, which notification admins may filter on, because support reads
     * deliveries and nobody reads somebody else's inbox.
     */
    @Test
    @DisplayName("the inbox owner is not filterable")
    void inboxOwnerIsNotFilterable() {
        noFields()
                .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("InboxItemEntity")
                .and().haveName("ownerUserId")
                .should().beAnnotatedWith(Filterable.class)
                .because("filterable means enumerable, and an inbox filtered by owner answers what "
                        + "the platform has told somebody else")
                .check(productionClasses);
    }
}
