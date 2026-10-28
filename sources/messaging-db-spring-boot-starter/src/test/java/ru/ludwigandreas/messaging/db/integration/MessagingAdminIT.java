package ru.ludwigandreas.messaging.db.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The {@code messaging} area through MVC dispatch and the administrative guard, over a real log schema.
 *
 * <p>The database log's own auto-configuration is left out - it would start listener containers this endpoint does
 * not need - and the {@link LogStore} is built here exactly as {@code LogStoreIT} builds it.
 */
@SpringBootTest(classes = MessagingAdminIT.Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.enabled=true",
            "ludwig.security.jwt.enabled=false",
            "spring.liquibase.enabled=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.autoconfigure.exclude="
                    + "ru.ludwigandreas.messaging.db.config.MessagingDbAutoConfiguration,"
                    + "ru.ludwigandreas.messaging.db.config.MessagingDbLiquibaseAutoConfiguration,"
                    + "ru.ludwigandreas.messaging.db.config.MessagingDbMetricsAutoConfiguration",
            "spring.kafka.bootstrap-servers=localhost:9",
            "ludwig.admin.grants.messaging.read[0]=OPS_SUPPORT"})
@AutoConfigureMockMvc
class MessagingAdminIT {

    private static final String SCHEMA = "admin_it";
    private static final ObjectMapper JSON = new ObjectMapper();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {

        @Bean
        DataSource dataSource() throws Exception {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(Containers.postgres().getJdbcUrl());
            source.setUser(Containers.postgres().getUsername());
            source.setPassword(Containers.postgres().getPassword());
            LogStoreIT.migrate(source, SCHEMA, "admin_it_changelog");
            return source;
        }

        @Bean
        LogStore logStore(DataSource dataSource) {
            return new LogStore(dataSource, new ObjectMapper(), SCHEMA, 2);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LogStore store;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyTheLog() {
        new JdbcTemplate(dataSource).execute("TRUNCATE " + SCHEMA + ".message_log, " + SCHEMA + ".partition_head, "
                + SCHEMA + ".consumer_offset");
    }

    @Test
    @DisplayName("each group's lag per partition is head minus committed offset, with no stored error")
    void lagPerPartition() throws Exception {
        store.ensurePositions("billing", List.of("it.orders"), true);
        byte[] key = "o-1".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            store.append("it.orders", key, new RecordHeaders(), ("v" + i).getBytes(StandardCharsets.UTF_8));
        }
        int partition = store.partitionFor(key);
        new JdbcTemplate(dataSource)
                .update("UPDATE " + SCHEMA + ".consumer_offset SET last_error = 'quotes a payload'");

        MvcResult result = mvc.perform(get("/admin/v1/messaging/consumers")
                .with(TestPrincipalBuilder.user("sam").roles("OPS_SUPPORT").postProcessor())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("quotes a payload", "lastError");
        JsonNode lagging = null;
        for (JsonNode row : JSON.readTree(body)) {
            if (row.path("partition").asInt() == partition) {
                lagging = row;
            }
        }
        assertThat(lagging).isNotNull();
        assertThat(lagging.path("groupId").asText()).isEqualTo("billing");
        assertThat(lagging.path("lag").asLong())
                .isEqualTo(lagging.path("headOffset").asLong() - lagging.path("committedOffset").asLong())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the area is read-only: no request but GET is mapped, and a caller without the grant is refused")
    void readOnly() throws Exception {
        var support = TestPrincipalBuilder.user("sam").roles("OPS_SUPPORT").postProcessor();

        assertThat(mvc.perform(post("/admin/v1/messaging/consumers").with(support)).andReturn().getResponse()
                .getStatus()).isEqualTo(405);
        assertThat(mvc.perform(delete("/admin/v1/messaging/consumers").with(support)).andReturn().getResponse()
                .getStatus()).isEqualTo(405);
        assertThat(mvc.perform(get("/admin/v1/messaging/consumers")
                .with(TestPrincipalBuilder.user("eve").roles("SOMEONE_ELSE").postProcessor())).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
    }
}
