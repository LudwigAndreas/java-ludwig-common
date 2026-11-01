package ru.ludwigandreas.identity.directory.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@code identity_directory_state}: one row per directory-fed source. */
public class DirectoryStateStore {

    private static final String ENSURE = "INSERT INTO identity_directory_state (source) VALUES (:source)"
            + " ON CONFLICT (source) DO NOTHING";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Creates the store.
     *
     * @param jdbc the database the projection lives in
     */
    public DirectoryStateStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A source's state.
     *
     * @param source the source
     * @return its state, or empty before its first run
     */
    public Optional<DirectoryState> read(String source) {
        List<DirectoryState> rows = jdbc.query("SELECT * FROM identity_directory_state WHERE source = :source",
                new MapSqlParameterSource("source", source), (rs, row) -> map(rs));
        return rows.stream().findFirst();
    }

    /**
     * Moves a source's watermark forward; never back. Call it in the transaction that applied the page it ends.
     *
     * @param source    the source
     * @param watermark the page's watermark
     */
    public void advanceWatermark(String source, Instant watermark) {
        MapSqlParameterSource params = new MapSqlParameterSource("source", source)
                .addValue("watermark", Timestamp.from(watermark));
        jdbc.update(ENSURE, params);
        jdbc.update("UPDATE identity_directory_state SET watermark = :watermark WHERE source = :source"
                + " AND (watermark IS NULL OR watermark < :watermark)", params);
    }

    /**
     * Records how a sync ended.
     *
     * @param source  the source
     * @param outcome {@code succeeded} or {@code failed}
     * @param error   the failure's type, or null
     * @param pages   pages read
     * @param changes changes applied
     */
    public void recordSync(String source, String outcome, String error, int pages, int changes) {
        MapSqlParameterSource params = new MapSqlParameterSource("source", source).addValue("outcome", outcome)
                .addValue("error", error).addValue("pages", pages).addValue("changes", changes)
                .addValue("at", Timestamp.from(Instant.now()));
        jdbc.update(ENSURE, params);
        jdbc.update("UPDATE identity_directory_state SET last_sync_at = :at, last_sync_outcome = :outcome,"
                + " last_sync_error = :error, last_sync_pages = :pages, last_sync_changes = :changes"
                + " WHERE source = :source", params);
    }

    /**
     * Records how a sweep ended.
     *
     * @param source   the source
     * @param outcome  {@code succeeded}, {@code aborted} or {@code failed}
     * @param error    the failure's type, or null
     * @param pages    pages read
     * @param disabled users disabled
     */
    public void recordSweep(String source, String outcome, String error, int pages, long disabled) {
        MapSqlParameterSource params = new MapSqlParameterSource("source", source).addValue("outcome", outcome)
                .addValue("error", error).addValue("pages", pages).addValue("disabled", (int) disabled)
                .addValue("at", Timestamp.from(Instant.now()));
        jdbc.update(ENSURE, params);
        jdbc.update("UPDATE identity_directory_state SET last_sweep_at = :at, last_sweep_outcome = :outcome,"
                + " last_sweep_error = :error, last_sweep_pages = :pages, last_sweep_disabled = :disabled"
                + " WHERE source = :source", params);
    }

    private static DirectoryState map(ResultSet rs) throws SQLException {
        return new DirectoryState(rs.getString("source"), instant(rs, "watermark"), instant(rs, "last_sync_at"),
                rs.getString("last_sync_outcome"), rs.getString("last_sync_error"),
                (Integer) rs.getObject("last_sync_pages"), (Integer) rs.getObject("last_sync_changes"),
                instant(rs, "last_sweep_at"), rs.getString("last_sweep_outcome"), rs.getString("last_sweep_error"),
                (Integer) rs.getObject("last_sweep_pages"), (Integer) rs.getObject("last_sweep_disabled"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
