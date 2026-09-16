package ru.ludwigandreas.archrules.fixture.bad.datastore.catalog.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;

/** Violation: SQL straight from the web layer. */
@RestController
public class JdbcController {

    private final JdbcTemplate jdbcTemplate;

    public JdbcController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int purge() {
        return jdbcTemplate.update("delete from product");
    }
}
