package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.pat.audit.PatAuditEvents;

/**
 * The lifecycle events, and the two properties that are easy to break without noticing.
 *
 * <p>The last two tests are the ones worth having. One asserts that <b>no event carries credential
 * material</b>, reflectively over every record in the class rather than over the ones somebody remembered -
 * so an event added later is covered the day it is written. The other asserts the category comes from the
 * platform registry rather than a local literal, which is the kind of thing that drifts silently.
 */
class PatAuditEventTest {

    @Test
    @DisplayName("issuance records who asked, which is not always the owner")
    void issuanceRecordsTheRequester() {
        AuditEvent event = new PatAuditEvents.Issued(
                "pat-1", "alice", "admin", "ci-deploy",
                Set.of("orders:read"), Set.of("deploy-service"),
                Instant.parse("2026-04-01T00:00:00Z")).toAuditEvent();

        assertThat(event.action()).isEqualTo("pat.issued");
        assertThat(event.actor().subject()).isEqualTo("alice");
        assertThat(event.resource().id()).isEqualTo("pat-1");
        // Recorded even when it equals the owner, so "tokens minted on behalf of someone else" is a filter
        // rather than a join against a separate source.
        assertThat(event.attributes()).containsEntry("issuedBy", "admin");
        assertThat(event.attributes()).containsEntry("audiences", Set.of("deploy-service"));
    }

    @Test
    @DisplayName("a non-expiring token records 'never' rather than a null nobody can query")
    void nonExpiringRecordsNever() {
        AuditEvent event = new PatAuditEvents.Issued(
                "pat-1", "alice", "alice", "n", Set.of("a"), Set.of("svc"), null).toAuditEvent();

        assertThat(event.attributes()).containsEntry("expiresAt", "never");
    }

    @Test
    @DisplayName("a refused issuance is DENIED, not FAILURE, and carries no token id")
    void refusedIssuanceIsDenied() {
        AuditEvent event =
                new PatAuditEvents.IssuanceRefused("bob", "alice", "scope-not-held").toAuditEvent();

        // DENIED rather than FAILURE: "we refused them" and "we broke" look identical in a boolean column
        // and lead to opposite investigations. The same distinction AccessDecision already draws.
        assertThat(event.outcome().status()).isEqualTo(AuditOutcome.Status.DENIED);
        assertThat(event.outcome().reason()).isEqualTo("scope-not-held");
        assertThat(event.resource().id()).isNull();
        assertThat(event.attributes()).containsEntry("requestedOwner", "bob");
    }

    @Test
    @DisplayName("revocation records its reason, because the reasons lead to different actions")
    void revocationRecordsItsReason() {
        AuditEvent event =
                new PatAuditEvents.Revoked("pat-1", "alice", null, "owner-disabled").toAuditEvent();

        assertThat(event.action()).isEqualTo("pat.revoked");
        // A system revocation records "system" rather than a null, so a query for "who revoked this" never
        // has to distinguish absent from unknown.
        assertThat(event.attributes()).containsEntry("revokedBy", "system");
    }

    @Test
    @DisplayName("a revoked token being presented is a denial and is the event that should alert")
    void revokedTokenPresentedIsADenial() {
        AuditEvent event =
                new PatAuditEvents.RevokedTokenPresented("pat-1", "alice", "10.1.2.3").toAuditEvent();

        // The uniform exchange failure tells the presenter nothing, which is exactly why this has to
        // reach the sink: presenting a revoked credential means something still holds it.
        assertThat(event.outcome().status()).isEqualTo(AuditOutcome.Status.DENIED);
        assertThat(event.attributes()).containsEntry("sourceIp", "10.1.2.3");
    }

    @Test
    @DisplayName("the three use signals exist and are distinct, because each means something different")
    void theThreeUseSignalsAreDistinct() {
        Instant then = Instant.parse("2025-01-01T00:00:00Z");

        assertThat(new PatAuditEvents.FirstUse("p", "a", "ip", "svc").toAuditEvent().action())
                .isEqualTo("pat.first-use");
        assertThat(new PatAuditEvents.DormantWake("p", "a", "ip", then).toAuditEvent().action())
                .isEqualTo("pat.dormant-wake");
        assertThat(new PatAuditEvents.UnseenSource("p", "a", "ip", "old").toAuditEvent().action())
                .isEqualTo("pat.unseen-source");
    }

    @Test
    @DisplayName("every event is filed under the platform's credential category, not a local literal")
    void everyEventUsesThePlatformCategory() throws Exception {
        for (AuditEvent event : allEvents()) {
            assertThat(event.category()).isEqualTo(AuditCategories.CREDENTIAL);
        }
    }

    @Test
    @DisplayName("no event carries a secret, a digest or a key id - checked over every record in the class")
    void noEventCarriesCredentialMaterial() throws Exception {
        // Reflective over every nested record rather than over the ones somebody remembered, so an event
        // added next year is covered on the day it is written. An audit trail is retained for years and
        // read by people not entitled to the payloads; it must not become a second copy of what it guards.
        for (AuditEvent event : allEvents()) {
            String rendered = event.toString().toLowerCase(java.util.Locale.ROOT);
            assertThat(rendered)
                    .as("%s must not carry credential material", event.action())
                    .doesNotContain("digest")
                    .doesNotContain("keyid")
                    .doesNotContain("secret")
                    .doesNotContain("lpat_");
        }
    }

    /**
     * One instance of every event in the class, built reflectively.
     *
     * <p>Reflective construction so the two whole-class assertions above cannot silently stop covering a
     * new event. The alternative - a hand-maintained list - is a list that falls behind, and the day it
     * falls behind is the day somebody adds the event that carries a digest.
     */
    private static java.util.List<AuditEvent> allEvents() throws Exception {
        java.util.List<AuditEvent> events = new java.util.ArrayList<>();
        for (Class<?> nested : PatAuditEvents.class.getDeclaredClasses()) {
            if (!nested.isRecord()) {
                continue;
            }
            Object[] arguments = new Object[nested.getRecordComponents().length];
            Class<?>[] types = new Class<?>[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                Class<?> type = nested.getRecordComponents()[i].getType();
                types[i] = type;
                if (type == String.class) {
                    arguments[i] = "value-" + i;
                } else if (type == Instant.class) {
                    arguments[i] = Instant.parse("2026-01-01T00:00:00Z");
                } else if (type == Set.class) {
                    arguments[i] = Set.of("element");
                } else {
                    throw new IllegalStateException("unhandled component type " + type
                            + " on " + nested.getSimpleName() + " - extend this builder rather than"
                            + " excluding the event, or the whole-class assertions stop covering it");
                }
            }
            Object instance = nested.getDeclaredConstructor(types).newInstance(arguments);
            Method toAuditEvent = nested.getDeclaredMethod("toAuditEvent");
            events.add((AuditEvent) toAuditEvent.invoke(instance));
        }
        assertThat(events).as("every event in the class must be constructible here").hasSize(10);
        return events;
    }
}
