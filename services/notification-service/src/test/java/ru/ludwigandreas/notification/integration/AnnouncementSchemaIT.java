package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The announcement schema as the migrations actually create it.
 *
 * <p>As with {@code InboxSchemaIT}, the ordinary "do the tables match the entities" question is
 * already answered by this class starting at all — the real Liquibase changelog runs against a real
 * PostgreSQL with Hibernate validating its mappings. What is asserted here is the part no entity
 * mapping mentions, and for this change that is where the load-bearing decisions live: the two check
 * constraints, the composite keys, the cascade directions and the read index.
 *
 * <p>The check constraints in particular are worth a test. An {@code EVERYONE} row with a stray role
 * code, or a {@code ROLE} row with none, would make the visibility predicate match the wrong set —
 * nobody, in the second case — silently, for the whole life of the announcement. That is not the kind
 * of defect a reviewer notices.
 */
class AnnouncementSchemaIT extends NotificationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a ROLE audience requires a role code")
    void roleAudienceRequiresAValue() {
        assertThatThrownBy(() -> insertAnnouncement("ROLE", null))
                .as("a null role code would make the predicate match nobody, silently")
                .hasMessageContaining("ck_notification_announcement_audience");
    }

    @Test
    @DisplayName("an EVERYONE audience must not carry a role code")
    void everyoneAudienceForbidsAValue() {
        assertThatThrownBy(() -> insertAnnouncement("EVERYONE", "ADMIN"))
                .as("a stray value here is ambiguous: is it everybody, or just the admins?")
                .hasMessageContaining("ck_notification_announcement_audience");
    }

    @Test
    @DisplayName("the two valid audience shapes are accepted")
    void validAudiencesAreAccepted() {
        assertThat(insertAnnouncement("EVERYONE", null)).isNotNull();
        assertThat(insertAnnouncement("ROLE", "ADMIN")).isNotNull();
    }

    /** A zero-length or inverted window would be visible to nobody while looking published. */
    @Test
    @DisplayName("the visibility window must be non-empty")
    void windowMustBeNonEmpty() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> insertAnnouncement("EVERYONE", null, now, now))
                .hasMessageContaining("ck_notification_announcement_window");
    }

    @Test
    @DisplayName("content is keyed by announcement and locale, so one language cannot be stored twice")
    void contentKeyIsCompositeAndUnique() {
        UUID id = insertAnnouncement("EVERYONE", null);

        insertContent(id, "en");
        insertContent(id, "ru");

        assertThatThrownBy(() -> insertContent(id, "en"))
                .as("a second English rendering would make the application choose between two")
                .hasMessageContaining("pk_notification_announcement_content");
        assertThat(countContent(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("a dismissal is keyed by announcement and owner, so it cannot be recorded twice")
    void markerKeyIsCompositeAndUnique() {
        UUID id = insertAnnouncement("EVERYONE", null);

        insertMarker(id, "user-1");

        assertThatThrownBy(() -> insertMarker(id, "user-1"))
                .as("idempotent dismissal should be a constraint, not a query")
                .hasMessageContaining("pk_notification_announcement_marker");
    }

    /**
     * {@code CASCADE} on both, which is the opposite answer to the inbox item's delivery foreign key
     * and for the opposite reason: there the delivery is purged first by design and the item must
     * survive it. Here the children are meaningless without the announcement, so they must go with
     * it — and that is what makes retention a constant number of rows regardless of audience size.
     * Postgres spells the actions {@code a}, {@code c}, {@code n}.
     */
    @Test
    @DisplayName("content and markers are deleted with the announcement")
    void childrenCascade() {
        assertThat(deleteActionOf("fk_notification_announcement_content")).isEqualTo("c");
        assertThat(deleteActionOf("fk_notification_announcement_marker")).isEqualTo("c");
    }

    @Test
    @DisplayName("deleting an announcement really removes its content and markers")
    void cascadeActuallyRemovesChildren() {
        UUID id = insertAnnouncement("EVERYONE", null);
        insertContent(id, "en");
        insertMarker(id, "user-1");

        jdbc.update("DELETE FROM notification_announcement WHERE id = ?", id);

        assertThat(countContent(id)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_announcement_marker WHERE announcement_id = ?",
                Integer.class, id)).isZero();
    }

    /**
     * The read path's index. {@code visible_until} leads because every read is bounded by it and in a
     * mature table most rows are expired, making it the most selective term. The audience is
     * deliberately absent — it is matched against a short in-memory list of the caller's roles, not
     * scanned.
     */
    @Test
    @DisplayName("the window index exists and leads with visible_until")
    void windowIndexExists() {
        String definition = indexDefinitionOf("ix_notification_announcement_window");

        assertThat(definition).isNotNull();
        assertThat(definition).contains("visible_until");
        assertThat(definition).contains("visible_from");
    }

    @Test
    @DisplayName("the marker index leads with the owner, which is how the read path asks")
    void markerIndexLeadsWithOwner() {
        String definition = indexDefinitionOf("ix_notification_announcement_marker_owner");

        assertThat(definition).isNotNull();
        assertThat(definition.indexOf("owner_user_id"))
                .as("the NOT EXISTS is per owner, so the owner must lead")
                .isLessThan(definition.indexOf("announcement_id"));
    }

    /**
     * No per-subject audience table exists, which is the schema-level statement of the property an
     * ArchUnit rule protects on the Java side. A table can be added by a changeset without any entity
     * mentioning it.
     */
    @Test
    @DisplayName("no materialized audience table exists")
    void noMaterializedAudienceTable() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_name LIKE ?",
                String.class, "notification_announcement%");

        assertThat(tables).containsExactlyInAnyOrder(
                "notification_announcement",
                "notification_announcement_content",
                "notification_announcement_marker",
                // The email fan-out's run table. One row per announcement, not per recipient - which
                // is why it belongs in this list rather than being an exception to it.
                "notification_announcement_email_run");
    }

    private UUID insertAnnouncement(String kind, String value) {
        Instant now = Instant.now();
        return insertAnnouncement(kind, value, now, now.plusSeconds(86_400));
    }

    private UUID insertAnnouncement(String kind, String value, Instant from, Instant until) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_announcement (id, category, category_class, "
                        + "template_key, audience_kind, audience_value, visible_from, visible_until, "
                        + "created_at, updated_at, created_by, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id, "platform-release", "PLATFORM", "release-notes", kind, value,
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(until),
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()),
                "publisher");
        return id;
    }

    private void insertContent(UUID announcementId, String locale) {
        jdbc.update("INSERT INTO notification_announcement_content (announcement_id, locale, "
                        + "subject, body_html, body_text, rendered_at) VALUES (?, ?, ?, ?, ?, ?)",
                announcementId, locale, "Subject", "<p>Body</p>", "Body",
                java.sql.Timestamp.from(Instant.now()));
    }

    private void insertMarker(UUID announcementId, String owner) {
        jdbc.update("INSERT INTO notification_announcement_marker (announcement_id, owner_user_id, "
                        + "dismissed_at) VALUES (?, ?, ?)",
                announcementId, owner, java.sql.Timestamp.from(Instant.now()));
    }

    private int countContent(UUID announcementId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification_announcement_content WHERE announcement_id = ?",
                Integer.class, announcementId);
    }

    private String deleteActionOf(String constraintName) {
        return jdbc.queryForObject(
                "SELECT confdeltype::text FROM pg_constraint WHERE conname = ?",
                String.class, constraintName);
    }

    private String indexDefinitionOf(String indexName) {
        List<String> definitions = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, indexName);
        return definitions.isEmpty() ? null : definitions.get(0);
    }
}
