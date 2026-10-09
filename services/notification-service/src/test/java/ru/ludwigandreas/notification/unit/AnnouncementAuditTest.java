package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.notification.service.announcement.AnnouncementAuditEvent;
import ru.ludwigandreas.notification.service.model.Audience;

/**
 * What an announcement's audit record says, and - just as importantly - what it does not.
 *
 * <h2>Why this matters more than most audit tests</h2>
 *
 * <p>Two of this change's rules cannot be mechanised: whether a configured category's class is the
 * right product answer, and whether the audience somebody chose was the one that was approved. For
 * both, the stated mitigation is that the decision has a named author. That mitigation is this
 * record, so what it contains is load-bearing rather than incidental.
 *
 * <h2>Why these assertions are here and not in an integration test</h2>
 *
 * <p>Substituting a recording sink into the integration context was tried twice and abandoned: the
 * platform already publishes a {@code @Primary} {@code AuditSink}, so a second one fails the shared
 * context with "more than one 'primary' bean found" and takes thirteen unrelated integration tests
 * with it. The questions being asked here - what the event carries - need no database, so asking
 * them in a unit test costs nothing and destabilises nothing.
 */
class AnnouncementAuditTest {

    private static final UUID ID = UUID.fromString("7c3f1a2b-3c4d-5e6f-7081-92a3b4c5d6e7");

    @Test
    @DisplayName("a publish records the audience as a kind and a value")
    void publishRecordsTheAudience() {
        AuditEvent event = AnnouncementAuditEvent.published(ID, "platform-release",
                Audience.ofRole("ADMIN"), Actor.of("publisher"), Map.of()).toAuditEvent();

        assertThat(event.action()).isEqualTo("config.announcement-published");
        assertThat(event.attributes()).containsEntry("audienceKind", "ROLE");
        assertThat(event.attributes()).containsEntry("audienceValue", "ADMIN");
        assertThat(event.attributes()).containsEntry("category", "platform-release");
        assertThat(event.resource().id()).isEqualTo(ID.toString());
        assertThat(event.actor().subject()).isEqualTo("publisher");
    }

    /**
     * An {@code EVERYONE} audience records no value, because it has none. A record that invented one
     * would be ambiguous in exactly the way the database check constraint exists to prevent.
     */
    @Test
    @DisplayName("an EVERYONE audience records no value")
    void everyoneRecordsNoValue() {
        AuditEvent event = AnnouncementAuditEvent.published(ID, "platform-release",
                Audience.everyone(), Actor.of("publisher"), Map.of()).toAuditEvent();

        assertThat(event.attributes()).containsEntry("audienceKind", "EVERYONE");
        assertThat(event.attributes()).doesNotContainKey("audienceValue");
    }

    /**
     * The assertion with the most consequence. The record must say what the author <em>decided</em> -
     * "everybody with ADMIN" - and not which particular people held that role at that second. A
     * resolved list would be the wrong fact, and it would also put personal data into a record kept
     * far longer than this service keeps anything else.
     */
    @Test
    @DisplayName("no resolved subject list is ever recorded")
    void noSubjectListIsRecorded() {
        AuditEvent event = AnnouncementAuditEvent.published(ID, "platform-release",
                Audience.ofRole("ADMIN"), Actor.of("publisher"),
                Map.of("visibleUntil", "2026-12-01T00:00:00Z")).toAuditEvent();

        assertThat(event.attributes())
                .doesNotContainKeys("subjects", "recipients", "userIds", "members", "audience");
    }

    @Test
    @DisplayName("a correction is a distinct action on the same resource")
    void correctionIsItsOwnAction() {
        AuditEvent event = AnnouncementAuditEvent.corrected(ID, "platform-release",
                Audience.everyone(), Actor.of("publisher")).toAuditEvent();

        assertThat(event.action()).isEqualTo("config.announcement-corrected");
        assertThat(event.resource().id()).isEqualTo(ID.toString());
    }

    /**
     * A cancelled broadcast records how many deliveries were already created, because that is the
     * number somebody asking "what went out?" needs - and the one a cancellation reported as a clean
     * stop would hide.
     */
    @Test
    @DisplayName("a cancelled broadcast records how many deliveries had already been created")
    void cancellationRecordsPartialOutcome() {
        AuditEvent event = AnnouncementAuditEvent.broadcastCancelled(ID, "platform-incident",
                Audience.everyone(), Actor.of("publisher"), 4_200L).toAuditEvent();

        assertThat(event.action()).isEqualTo("config.announcement-broadcast-cancelled");
        // A string, deliberately - see the factory's javadoc. A numeric value in the JSON attributes
        // map does not round-trip to the same type, and the resulting dirty check fails the whole
        // transaction at commit against AuditEventEntity's immutability listener.
        assertThat(event.attributes()).containsEntry("deliveriesAlreadyCreated", "4200");
    }

    @Test
    @DisplayName("an absent actor becomes the system actor rather than null")
    void absentActorBecomesSystem() {
        AuditEvent event = AnnouncementAuditEvent.published(ID, "platform-release",
                Audience.everyone(), null, Map.of()).toAuditEvent();

        assertThat(event.actor()).isNotNull();
        assertThat(event.actor().subject()).isEqualTo(Actor.system().subject());
    }

    /**
     * The sink is the platform's own interface, which is what keeps this a test of the one audit
     * mechanism rather than of a second one. {@link RecordingAuditSink} implements it and nothing
     * else.
     */
    @Test
    @DisplayName("the event goes to the platform's AuditSink interface")
    void eventGoesToThePlatformSink() {
        RecordingAuditSink sink = new RecordingAuditSink();

        sink.record(AnnouncementAuditEvent.published(ID, "platform-release", Audience.everyone(),
                Actor.of("publisher"), Map.of()).toAuditEvent());

        assertThat(sink.actions()).containsExactly("config.announcement-published");
        assertThat(sink.attributesOf("config.announcement-published"))
                .containsEntry("audienceKind", "EVERYONE");
    }
}
