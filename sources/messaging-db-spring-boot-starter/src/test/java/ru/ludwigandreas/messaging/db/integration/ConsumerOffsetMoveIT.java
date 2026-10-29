package ru.ludwigandreas.messaging.db.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.messaging.db.admin.service.MessagingAdminService;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.messaging.db.log.Outcome;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * Pausing, resuming and moving a database-log consumer through MVC dispatch and the administrative guard.
 *
 * <p>As in {@code MessagingAdminIT} the log's own auto-configuration is left out, so there is no listener registry to
 * name the service's groups; the service is declared here with {@code billing} as its only local group.
 */
@SpringBootTest(classes = ConsumerOffsetMoveIT.Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
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
            "ludwig.admin.grants.messaging.read[0]=OPS_SUPPORT",
            "ludwig.admin.grants.messaging.read[1]=OPS_ADMIN",
            "ludwig.admin.grants.messaging.operate[0]=OPS_SUPPORT",
            "ludwig.admin.grants.messaging.operate[1]=OPS_ADMIN",
            "ludwig.admin.grants.messaging.privileged[0]=OPS_ADMIN"})
@AutoConfigureMockMvc
class ConsumerOffsetMoveIT {

    private static final String SCHEMA = "move_it";
    private static final String TOPIC = "it.orders";
    private static final String BASE = "/admin/v1/messaging/consumers/billing/topics/" + TOPIC;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {

        @Bean
        DataSource dataSource() throws Exception {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(Containers.postgres().getJdbcUrl());
            source.setUser(Containers.postgres().getUsername());
            source.setPassword(Containers.postgres().getPassword());
            LogStoreIT.migrate(source, SCHEMA, "move_it_changelog");
            return source;
        }

        @Bean
        LogStore logStore(DataSource dataSource) {
            // One partition, so offsets are simply 0, 1, 2.
            return new LogStore(dataSource, new ObjectMapper(), SCHEMA, 1);
        }

        @Bean
        MessagingAdminService messagingAdminService(LogStore store, AdminAudit audit) {
            return new MessagingAdminService(store, () -> Set.of("billing"), audit);
        }

        @Bean
        AuditSink capturingAuditSink() {
            return AUDIT::add;
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
                + SCHEMA + ".consumer_offset, " + SCHEMA + ".consumer_pause");
        AUDIT.clear();
        store.ensurePositions("billing", List.of(TOPIC), true);
        for (String value : List.of("o1", "poison", "o3")) {
            store.append(TOPIC, "k".getBytes(StandardCharsets.UTF_8), new RecordHeaders(),
                    value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static RequestPostProcessor support() {
        return TestPrincipalBuilder.user("sam").roles("OPS_SUPPORT").postProcessor();
    }

    private static RequestPostProcessor admin() {
        return TestPrincipalBuilder.user("olga").roles("OPS_ADMIN").postProcessor();
    }

    private MvcResult pause(RequestPostProcessor caller) throws Exception {
        return mvc.perform(post(BASE + "/pause").header(AdminInvocation.REASON_HEADER, "poison record")
                .with(caller)).andReturn();
    }

    private MvcResult move(String body, RequestPostProcessor caller) throws Exception {
        return mvc.perform(post(BASE + "/offsets").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(AdminInvocation.REASON_HEADER, "INC-42").with(caller)).andReturn();
    }

    private List<String> drain() {
        List<String> delivered = new ArrayList<>();
        while (store.processNext("billing", List.of(TOPIC), (position, record) -> {
            delivered.add(new String(record.payload(), StandardCharsets.UTF_8));
            return new Outcome.Advance();
        })) {
            // keep claiming until nothing is left
        }
        return delivered;
    }

    private List<AuditEvent> moves() {
        return AUDIT.stream().filter(event -> "move".equals(event.attributes().get("action"))).toList();
    }

    @Test
    @DisplayName("a poison record is skipped by pausing, moving past it and resuming; the listing shows the pause")
    void skipPoisonRecord() throws Exception {
        assertThat(pause(support()).getResponse().getStatus()).isEqualTo(200);
        JsonNode listed = JSON.readTree(mvc.perform(get("/admin/v1/messaging/consumers").with(support()))
                .andReturn().getResponse().getContentAsString()).get(0);
        assertThat(listed.path("paused").asBoolean()).isTrue();
        assertThat(listed.path("pausedBy").asText()).isEqualTo("sam");

        MvcResult moved = move("{\"to\":\"offset\",\"value\":\"2\"}", admin());

        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        JsonNode partition = JSON.readTree(moved.getResponse().getContentAsString()).path("partitions").get(0);
        assertThat(partition.path("from").asLong()).isEqualTo(-1);
        assertThat(partition.path("to").asLong()).isEqualTo(1);
        assertThat(partition.path("skipped").asLong()).isEqualTo(2);
        assertThat(moves()).singleElement().satisfies(event -> {
            assertThat(event.resource().id()).isEqualTo("billing/" + TOPIC);
            assertThat(event.attributes()).containsEntry("to", "OFFSET").containsEntry("skipped", 2L)
                    .containsEntry("reason", "INC-42");
        });

        assertThat(mvc.perform(post(BASE + "/resume").with(support())).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(drain()).containsExactly("o3");
    }

    @Test
    @DisplayName("a group handled past a timestamp replays from there once moved back")
    void replayByTimestamp() throws Exception {
        assertThat(drain()).containsExactly("o1", "poison", "o3");
        pause(support());

        MvcResult moved = move("{\"to\":\"timestamp\",\"value\":\"1970-01-01T00:00:00Z\"}", admin());

        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(moved.getResponse().getContentAsString()).path("partitions").get(0)
                .path("replayed").asLong()).isEqualTo(3);
        mvc.perform(post(BASE + "/resume").with(support()));
        assertThat(drain()).containsExactly("o1", "poison", "o3");
    }

    @Test
    @DisplayName("a move of a group that is not paused is a 409, audited as refused, and changes nothing")
    void notPaused() throws Exception {
        MvcResult moved = move("{\"to\":\"latest\"}", admin());

        assertThat(moved.getResponse().getStatus()).isEqualTo(409);
        assertThat(moves()).singleElement()
                .satisfies(event -> assertThat(event.outcome().reason()).isEqualTo("refused-by-state"));
        assertThat(drain()).containsExactly("o1", "poison", "o3");
    }

    @Test
    @DisplayName("a group this service does not consume as is a 404; an operate-only caller cannot move; a bad target"
            + " is a 400")
    void refusals() throws Exception {
        assertThat(mvc.perform(post("/admin/v1/messaging/consumers/search/topics/" + TOPIC + "/pause")
                .with(support())).andReturn().getResponse().getStatus()).isEqualTo(404);
        pause(support());

        assertThat(move("{\"to\":\"latest\"}", support()).getResponse().getStatus()).isEqualTo(403);
        assertThat(move("{\"to\":\"sideways\"}", admin()).getResponse().getStatus()).isEqualTo(400);
        assertThat(move("{\"to\":\"offset\",\"value\":\"x\"}", admin()).getResponse().getStatus()).isEqualTo(400);
        assertThat(drain()).isEmpty();
    }
}
