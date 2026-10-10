package ru.ludwigandreas.testsupport.local.integration.sample;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The sample's repository, which its seed fixtures write through.
 *
 * <p>Plain JDBC keeps the sample to one table and no ORM. The platform's QueryDSL-only rule is about
 * a service's repositories; this is a test double for one, in a module that ships no service.
 */
@Repository
public class WidgetRepository {

    private final JdbcTemplate jdbc;

    public WidgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Widget widget) {
        jdbc.update("INSERT INTO sample_widget (code, note) VALUES (?, ?)", widget.code(), widget.note());
    }

    public void annotateAll(String note) {
        jdbc.update("UPDATE sample_widget SET note = ?", note);
    }

    public List<Widget> findAll() {
        return jdbc.query("SELECT code, note FROM sample_widget ORDER BY code",
                (row, index) -> new Widget(row.getString("code"), row.getString("note")));
    }
}
