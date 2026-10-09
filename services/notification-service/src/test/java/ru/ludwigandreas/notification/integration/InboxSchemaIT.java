package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The inbox schema as the migrations actually create it in PostgreSQL.
 *
 * <p>Most of what matters is already proved by this class starting at all: every integration test
 * here runs the real Liquibase changelog against a real PostgreSQL with Hibernate validating its
 * mappings against the result, so a column this service's entities expect and the changelog does not
 * create fails the context before any assertion runs. That covers the ordinary "do the tables match
 * the entities" question better than a bespoke test could.
 *
 * <p>What it does not cover is the part of the schema no entity mapping mentions, which is exactly
 * where this change's load-bearing decisions live: the two foreign keys' delete actions, and the
 * indexes the read path and the purge depend on. Those are asserted here because getting either
 * wrong is silent - the wrong delete action shows up as somebody's unread inbox vanishing on the day
 * the delivery purge first catches up with it, and a missing index shows up as a slow endpoint rather
 * than a failure.
 *
 * <p>Executing the {@code --rollback} statements is deliberately not covered here. Both are plain
 * {@code DROP TABLE} of a table this changeset created, {@code scripts/check_migrations.sh} fails the
 * build if either is absent, and standing a second container up to prove that Postgres can drop a
 * table it has just created would be a test of Postgres.
 */
class InboxSchemaIT extends NotificationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * {@code ON DELETE SET NULL}, and the single most consequential line in the changelog.
     *
     * <p>The delivery retention window runs from creation (90 days) while the inbox window is
     * anchored on being read, so the delivery is the row that disappears first and an item outliving
     * its delivery is the normal case. {@code CASCADE} here would let the delivery purge silently
     * delete live unread notifications; {@code NO ACTION} would make the purge itself fail. Postgres
     * spells the actions {@code a} (no action), {@code c} (cascade), {@code n} (set null).
     */
    @Test
    @DisplayName("an item survives the purge of the delivery it came from")
    void deliveryForeignKeyIsSetNull() {
        assertThat(deleteActionOf("fk_notification_inbox_delivery")).isEqualTo("n");
    }

    /**
     * {@code ON DELETE CASCADE}, the opposite answer to the one above, for the opposite reason: the
     * content's primary key <em>is</em> the item's, so a body without its item is unreachable rather
     * than merely orphaned.
     */
    @Test
    @DisplayName("content is deleted with the item that owns it")
    void contentForeignKeyCascades() {
        assertThat(deleteActionOf("fk_notification_inbox_content_item")).isEqualTo("c");
    }

    @Test
    @DisplayName("the owner-scoped index the list and the unread count both use exists")
    void ownerIndexExists() {
        String definition = indexDefinitionOf("ix_notification_inbox_owner");

        assertThat(definition).isNotNull();
        // Owner first, because every query is scoped to exactly one owner and none may span owners.
        assertThat(definition).contains("owner_user_id");
        assertThat(definition).contains("created_at DESC");
    }

    /**
     * The purge's index is partial, and the predicate is the point. Retention is anchored on being
     * read or dismissed, so an unread item must never be reachable by an age-based sweep - indexing
     * only the settled rows is what keeps that sweep away from them.
     */
    @Test
    @DisplayName("the retention index covers only items that have been read or dismissed")
    void retentionIndexIsPartialOverSettledItems() {
        String definition = indexDefinitionOf("ix_notification_inbox_settled");

        assertThat(definition).isNotNull();
        assertThat(definition).contains("WHERE");
        assertThat(definition).contains("read_at IS NOT NULL");
        assertThat(definition).contains("dismissed_at IS NOT NULL");
    }

    @Test
    @DisplayName("the owner column is not nullable, so an unreadable item cannot be stored")
    void ownerIsMandatory() {
        assertThat(isNullable("notification_inbox_item", "owner_user_id")).isEqualTo("NO");
        // Nullable by design, and routinely null - see deliveryForeignKeyIsSetNull above.
        assertThat(isNullable("notification_inbox_item", "delivery_id")).isEqualTo("YES");
        // All three read-state instants start unset and are what the recipient later moves.
        assertThat(isNullable("notification_inbox_item", "read_at")).isEqualTo("YES");
        assertThat(isNullable("notification_inbox_item", "dismissed_at")).isEqualTo("YES");
    }

    /**
     * The read state belongs to the inbox item and must never appear on the delivery, which is a hot
     * claim-query row rather than a user-owned document. An ArchUnit rule says the same thing about
     * the Java side; this says it about the table, because a column can be added by a changeset
     * without any entity mentioning it.
     */
    @Test
    @DisplayName("no read-state column leaked onto the delivery table")
    void deliveryTableHasNoReadState() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, "notification_delivery");

        assertThat(columns).doesNotContain("read_at", "seen_at", "dismissed_at");
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

    private String isNullable(String table, String column) {
        return jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                String.class, table, column);
    }
}
