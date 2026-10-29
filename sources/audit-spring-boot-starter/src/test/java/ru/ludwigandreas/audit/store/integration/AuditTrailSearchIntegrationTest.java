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
        recorded(actor, tenant, null, resourceId, at);
    }

    private void recorded(String actor, String tenant, String via, String resourceId, Instant at) {
        sink.record(AuditEvent.builder().category(CATEGORY).action("partner.suspended")
                .actor(new Actor(actor, "USER", null, null, tenant, via))
                .resource(new Resource("partner", resourceId, null)).occurredAt(at).build());
    }

    private static MockHttpServletRequestBuilder filtered(String expression) {
        return get(EVENTS).param("$filter", expression);
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

        JsonNode acme = page(filtered("category eq '" + CATEGORY + "'").with(auditorOf("acme")));
        JsonNode service = page(filtered("category eq '" + CATEGORY + "'").with(auditService()));

        assertThat(acme.path("items").findValuesAsText("actorSubject")).containsExactly("alice");
        assertThat(service.path("items").findValuesAsText("actorSubject"))
                .containsExactly("alice", "gary", "system-job");
    }

    @Test
    @DisplayName("everything done through the administrative UI yesterday, and only that")
    void throughTheUiYesterday() throws Exception {
        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant yesterday = startOfToday.minus(1, ChronoUnit.DAYS);
        recorded("alice", "acme", "admin-console", "p-1", yesterday.plus(9, ChronoUnit.HOURS));
        recorded("alice", "acme", null, "p-2", yesterday.plus(10, ChronoUnit.HOURS));
        recorded("bob", "acme", "ops-portal", "p-3", yesterday.plus(11, ChronoUnit.HOURS));
        recorded("alice", "acme", "admin-console", "p-4", startOfToday.plus(1, ChronoUnit.MINUTES));

        JsonNode result = page(filtered("actorVia eq 'admin-console' and occurredAt ge "
                + yesterday + " and occurredAt lt " + startOfToday).with(auditService()));

        assertThat(result.path("items").findValuesAsText("resourceId")).containsExactly("p-1");
        assertThat(result.path("items").get(0).path("via").asText()).isEqualTo("admin-console");
    }

    @Test
    @DisplayName("who touched this partner: newest first, paged by cursor without repeats")
    void resourceHistoryPaged() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            recorded("alice", "acme", "acme-logistics", now.minus(i, ChronoUnit.MINUTES));
        }

        List<String> seen = new ArrayList<>();
        JsonNode first = page(filtered("resourceType eq 'partner' and resourceId eq 'acme-logistics'")
                .param("size", "2").with(auditService()));
        first.path("items").forEach(item -> seen.add(item.path("id").asText()));
        String cursor = first.path("next").asText();
        JsonNode second = page(filtered("resourceType eq 'partner' and resourceId eq 'acme-logistics'")
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
    @DisplayName("each search leaves one admin event with its filter's paths and operators, never its values")
    void searchIsRecorded() throws Exception {
        recorded("alice", "acme", "p-1", Instant.now().minus(1, ChronoUnit.HOURS));

        page(filtered("actorSubject eq 'alice'").with(auditService()));

        JsonNode trail = page(filtered("category eq 'admin'").with(auditService()));
        JsonNode search = trail.path("items").get(trail.path("items").size() - 1);
        assertThat(search.path("action").asText()).isEqualTo("audit.read");
        assertThat(search.path("attributes").path("filterPaths").toString()).isEqualTo("[\"actorSubject\"]");
        assertThat(search.path("attributes").path("filterOperators").toString()).isEqualTo("[\"actorSubject eq\"]");
        assertThat(search.path("attributes").toString()).doesNotContain("alice");
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

        assertThat(page(filtered("category eq '" + CATEGORY + "'").with(auditorOf("acme"))).path("items")).isEmpty();
        assertThat(page(filtered("category eq '" + CATEGORY + "'").with(auditService())).path("items")
                .findValuesAsText("id")).contains(legacy.toString());
    }

    @Test
    @DisplayName("a filter cannot widen tenant confinement: another tenant's actor stays invisible")
    void filterCannotWiden() throws Exception {
        recorded("gary", "globex", "p-2", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(page(filtered("actorSubject eq 'gary'").with(auditorOf("acme")))
                .path("items")).isEmpty();
        assertThat(page(filtered("actorSubject eq 'gary'").with(auditService()))
                .path("items").findValuesAsText("actorSubject")).containsExactly("gary");
    }

    @Test
    @DisplayName("an occurred-at filter lifts the 30-day default; without one, older events stay out")
    void windowLiftedByFilter() throws Exception {
        recorded("alice", "acme", "p-old", Instant.now().minus(40, ChronoUnit.DAYS));
        String since = Instant.now().minus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();

        assertThat(page(filtered("resourceId eq 'p-old'").with(auditService())).path("items"))
                .isEmpty();
        assertThat(page(filtered("resourceId eq 'p-old' and occurredAt ge " + since)
                .with(auditService())).path("items").findValuesAsText("resourceId")).containsExactly("p-old");
    }

    @Test
    @DisplayName("a free-text field is not filterable, and offset paging is refused")
    void refusals() throws Exception {
        assertThat(mvc.perform(filtered("reason eq 'x'").with(auditService())).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get(EVENTS).param("$skip", "40").with(auditService())).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
    }
}
