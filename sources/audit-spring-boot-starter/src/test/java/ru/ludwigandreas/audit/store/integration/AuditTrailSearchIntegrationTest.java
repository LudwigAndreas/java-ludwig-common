package ru.ludwigandreas.audit.store.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * Searching the stored trail through the administrative surface: tenant confinement, paging, and the search's own
 * record.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.application.name=audit-test",
        "ludwig.audit.retention.enabled=false",
        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml",
        "spring.jpa.hibernate.ddl-auto=validate",
        "ludwig.security.jwt.enabled=false",
        "ludwig.admin.grants.audit.read[0]=AUDITOR"})
@Import(PostgresContainerConfiguration.class)
@ContextConfiguration(initializers = LegacyAuditTablesInitializer.class)
@AutoConfigureMockMvc
class AuditTrailSearchIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENTS = "/admin/v1/audit/events";
    private static final String CATEGORY = "partner";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuditSink sink;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyTheTrail() {
        new JdbcTemplate(dataSource).execute("DELETE FROM audit_event");
    }

    private void recorded(String actor, String tenant, String resourceId, Instant at) {
        sink.record(AuditEvent.builder().category(CATEGORY).action("partner.suspended")
                .actor(new Actor(actor, "USER", null, null, tenant))
                .resource(new Resource("partner", resourceId, null)).occurredAt(at).build());
    }

    private static RequestPostProcessor auditorOf(String tenant) {
        return TestPrincipalBuilder.user("anna").tenant(tenant).roles("AUDITOR").postProcessor();
    }

    private static RequestPostProcessor auditService() {
        return TestPrincipalBuilder.service("spiffe://mesh/ns/ops/sa/audit-console").roles("AUDITOR").postProcessor();
    }

    private JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a tenant administrator sees only their tenant's events; a service sees every tenant's")
    void tenantConfinement() throws Exception {
        Instant now = Instant.now();
        recorded("alice", "acme", "p-1", now.minus(1, ChronoUnit.HOURS));
        recorded("gary", "globex", "p-2", now.minus(2, ChronoUnit.HOURS));
        recorded("system-job", null, "p-3", now.minus(3, ChronoUnit.HOURS));

        JsonNode acme = page(get(EVENTS).param("category", CATEGORY).with(auditorOf("acme")));
        JsonNode service = page(get(EVENTS).param("category", CATEGORY).with(auditService()));

        assertThat(acme.path("items").findValuesAsText("actorSubject")).containsExactly("alice");
        assertThat(service.path("items").findValuesAsText("actorSubject"))
                .containsExactly("alice", "gary", "system-job");
    }

    @Test
    @DisplayName("who touched this partner: newest first, paged by cursor without repeats")
    void resourceHistoryPaged() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            recorded("alice", "acme", "acme-logistics", now.minus(i, ChronoUnit.MINUTES));
        }

        List<String> seen = new ArrayList<>();
        JsonNode first = page(get(EVENTS).param("resourceType", "partner").param("resourceId", "acme-logistics")
                .param("size", "2").with(auditService()));
        first.path("items").forEach(item -> seen.add(item.path("id").asText()));
        String cursor = first.path("next").asText();
        JsonNode second = page(get(EVENTS).param("resourceType", "partner").param("resourceId", "acme-logistics")
                .param("size", "2").param("cursor", cursor).with(auditService()));
        second.path("items").forEach(item -> seen.add(item.path("id").asText()));

        assertThat(seen).hasSize(4).doesNotHaveDuplicates();
        assertThat(Instant.parse(first.path("items").get(0).path("occurredAt").asText()))
                .isAfter(Instant.parse(first.path("items").get(1).path("occurredAt").asText()));
    }

    @Test
    @DisplayName("an oversized page is a 400, and a caller without audit.read is a 403")
    void limits() throws Exception {
        assertThat(mvc.perform(get(EVENTS).param("size", "1000").with(auditService())).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get(EVENTS).with(TestPrincipalBuilder.user("eve").roles("NOBODY").postProcessor()))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("each search leaves one admin event with its filter and result count, and none of the results")
    void searchIsRecorded() throws Exception {
        recorded("alice", "acme", "p-1", Instant.now().minus(1, ChronoUnit.HOURS));

        page(get(EVENTS).param("actor", "alice").with(auditService()));

        JsonNode trail = page(get(EVENTS).param("category", "admin").with(auditService()));
        JsonNode search = trail.path("items").get(trail.path("items").size() - 1);
        assertThat(search.path("action").asText()).isEqualTo("audit.read");
        assertThat(search.path("attributes").path("actor").asText()).isEqualTo("alice");
        assertThat(search.path("attributes").path("returned").asInt()).isEqualTo(1);
        assertThat(search.path("resourceId").isNull()).isTrue();
    }

    @Test
    @DisplayName("events stored before the actor-tenant column are hidden from a tenant administrator")
    void preColumnRows() throws Exception {
        UUID legacy = UUID.randomUUID();
        new JdbcTemplate(dataSource).update("INSERT INTO audit_event (id, imported_at, source_system, occurred_at,"
                + " category, action, actor_subject, outcome)"
                + " VALUES (?, now(), 'legacy', now(), ?, 'x', 'old', 'SUCCESS')",
                legacy, CATEGORY);

        assertThat(page(get(EVENTS).param("category", CATEGORY).with(auditorOf("acme"))).path("items")).isEmpty();
        assertThat(page(get(EVENTS).param("category", CATEGORY).with(auditService())).path("items")
                .findValuesAsText("id")).contains(legacy.toString());
    }
}
