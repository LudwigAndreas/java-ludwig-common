package ru.ludwigandreas.fileaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The changelog applies, and the schema is the one the entities and the claim query expect.
 *
 * <p>Worth its own test because a Liquibase changelog is the one part of a module that no amount of compiling
 * checks. A column the entity declares and the changeset does not creates a context that starts and then fails on
 * the first insert; an index the claim query needs and the changeset lacks turns every worker tick into a
 * sequential scan, which nothing fails on at all - it just gets slower as the table grows.
 */
@SpringBootTest(classes = FileActionTestApplication.class,
        properties = {
            "ludwig.file-action.storage.uploads=file:///tmp/ludwig-file-action-it/uploads",
            "ludwig.file-action.storage.artifacts=file:///tmp/ludwig-file-action-it/artifacts",
            "ludwig.file-action.scanning.mode=DISABLED",
            "ludwig.file-action.actions.order-import.commit-policy=PER_ROW",
            "ludwig.file-action.actions.order-import.mode=DIRECT"
        })
class FileActionSchemaIT extends PostgresBackedTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("both tables exist, under the names the entities map to")
    void tablesExist() {
        assertThat(tableNames()).contains("file_action_submission", "file_action_row_reject");
    }

    @Test
    @DisplayName("the submission table carries every column the entity declares")
    void submissionColumns() {
        assertThat(columnsOf("file_action_submission")).contains(
                "id", "action", "state", "object_uri", "content_sha256", "size_bytes",
                "declared_filename", "declared_content_type", "source_format", "sheet", "submitted_by",
                "submitted_at", "started_at", "finished_at", "expires_at", "locale", "zone",
                "correlation_id", "rows_read", "rows_applied", "rows_rejected", "rows_skipped",
                "failure_code", "failure_args", "bound_rows_uri", "error_report_uri", "locked_by",
                "lease_expires_at", "attempts", "cancellation_requested", "created_at", "updated_at",
                "version");
    }

    @Test
    @DisplayName("the reject table carries every column the entity declares")
    void rejectColumns() {
        assertThat(columnsOf("file_action_row_reject")).contains(
                "id", "submission_id", "sheet", "displayed_row", "column_header", "code", "args",
                "created_at");
    }

    @Test
    @DisplayName("the dedup constraint is unique on action, content hash and submitter")
    void dedupConstraintExists() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where tablename = 'file_action_submission'",
                String.class);

        assertThat(indexes)
                .as("without this index a user double-clicking the drop zone creates the orders twice")
                .contains("ux_file_action_submission_content");
    }

    @Test
    @DisplayName("the claim query's index exists, because without it every worker tick is a sequential scan")
    void claimIndexExists() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where tablename = 'file_action_submission'",
                String.class);

        assertThat(indexes).contains("ix_file_action_submission_claim",
                "ix_file_action_submission_expiry");
    }

    @Test
    @DisplayName("the changeset ids are namespaced, so they cannot collide with a consumer's own")
    void changesetsAreNamespaced() {
        List<String> ids = jdbc.queryForList(
                "select id from databasechangelog where author = 'ludwig-file-action'", String.class);

        assertThat(ids)
                .as("a shared DATABASECHANGELOG table is why the ids and the author are namespaced")
                .isNotEmpty()
                .allSatisfy(id -> assertThat(id).startsWith("file-action-"));
    }

    @Test
    @DisplayName("a reject is deleted with its submission, so a purge leaves no orphans")
    void rejectsCascade() {
        List<String> rule = jdbc.queryForList(
                "select rc.delete_rule from information_schema.referential_constraints rc"
                        + " join information_schema.table_constraints tc"
                        + " on tc.constraint_name = rc.constraint_name"
                        + " where tc.table_name = 'file_action_row_reject'", String.class);

        assertThat(rule).contains("CASCADE");
    }

    private List<String> tableNames() {
        return jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = ?",
                String.class, table);
    }
}
