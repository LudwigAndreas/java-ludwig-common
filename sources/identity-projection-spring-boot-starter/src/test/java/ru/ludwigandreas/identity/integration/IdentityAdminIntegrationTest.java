package ru.ludwigandreas.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.repository.SecurityGrantRepository;
import ru.ludwigandreas.identity.repository.SecurityPartnerRepository;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The {@code identity} area through MVC dispatch and the administrative guard, over the projection's real tables.
 */
@SpringBootTest(classes = IdentityAdminIntegrationTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.identity.kafka.enabled=false",
            "ludwig.security.jwt.enabled=false",
            "spring.liquibase.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "ludwig.admin.grants.identity.read[0]=ACCESS_SUPPORT",
            "ludwig.admin.grants.identity.read[1]=ACCESS_ADMIN",
            "ludwig.admin.grants.identity.privileged[0]=ACCESS_ADMIN"})
@Import(PostgresContainerConfiguration.class)
@AutoConfigureMockMvc
class IdentityAdminIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/admin/v1/identity";

    static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();

    /** An order the data-scope machinery can confine by tenant - the dimension a grant may name. */
    record Order(String tenant) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {

        /** What a consuming service declares, as crud-service-example's QuerydslConfig does. */
        @Bean
        JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
            return new JPAQueryFactory(entityManager);
        }

        @Bean
        AuditSink capturingAuditSink() {
            return AUDIT::add;
        }

        @Bean
        DataScopeMapping<Order> orderScope() {
            return DataScopeMapping.forResource("order", Order.class)
                    .tenant(Expressions.stringPath("tenant"), Order::tenant)
                    .build();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityUserRepository users;

    @Autowired
    private SecurityPartnerRepository partners;

    @Autowired
    private SecurityGrantRepository grants;

    @BeforeEach
    void reset() {
        grants.deleteAll();
        partners.deleteAll();
        users.deleteAll();
        AUDIT.clear();
        user("bob", "acme", "ORDER_CLERK");
        user("gina", "globex", "ORDER_CLERK");
    }

    private void user(String subject, String tenant, String... roles) {
        SecurityUserEntity user = new SecurityUserEntity();
        user.setId(subject);
        user.setTenantId(tenant);
        user.setDisplayName(subject);
        user.setSourceSystem("oidc");
        user.setRoles(Set.of(roles));
        users.save(user);
    }

    private static RequestPostProcessor admin() {
        return TestPrincipalBuilder.user("alice").tenant("acme").roles("ACCESS_ADMIN").postProcessor();
    }

    private static RequestPostProcessor support() {
        return TestPrincipalBuilder.user("sam").tenant("acme").roles("ACCESS_SUPPORT").postProcessor();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static List<AuditEvent> adminEvents() {
        return AUDIT.stream().filter(event -> "admin".equals(event.category())).toList();
    }

    @Test
    @DisplayName("a partner is onboarded and suspended with a reason, each change audited")
    void partnerLifecycle() throws Exception {
        MvcResult onboarded = perform(json(post(BASE + "/partners"),
                "{\"code\":\"acme-logistics\",\"displayName\":\"Acme Logistics\",\"roles\":[\"PARTNER_ORDERS\"]}")
                .header(AdminInvocation.REASON_HEADER, "contract signed").with(admin()));
        MvcResult suspended = perform(post(BASE + "/partners/acme-logistics/suspend")
                .header(AdminInvocation.REASON_HEADER, "contract ended").with(admin()));

        assertThat(onboarded.getResponse().getStatus()).isEqualTo(201);
        assertThat(suspended.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(suspended.getResponse().getContentAsString()).path("status").asText())
                .isEqualTo("SUSPENDED");
        assertThat(adminEvents()).extracting(event -> event.attributes().get("reason"))
                .containsExactly("contract signed", "contract ended");
        assertThat(adminEvents()).allSatisfy(event -> assertThat(event.resource().id()).isEqualTo("acme-logistics"));
    }

    @Test
    @DisplayName("a replace naming a stale version is a 412 and changes nothing")
    void stalePartnerReplace() throws Exception {
        perform(json(post(BASE + "/partners"), "{\"code\":\"p1\",\"displayName\":\"One\"}")
                .header(AdminInvocation.REASON_HEADER, "onboard").with(admin()));
        String etag = perform(get(BASE + "/partners/p1").with(support())).getResponse().getHeader("ETag");

        MvcResult first = perform(json(put(BASE + "/partners/p1"), "{\"displayName\":\"One, renamed\"}")
                .header("If-Match", etag).header(AdminInvocation.REASON_HEADER, "rename").with(admin()));
        MvcResult second = perform(json(put(BASE + "/partners/p1"), "{\"displayName\":\"Lost update\"}")
                .header("If-Match", etag).header(AdminInvocation.REASON_HEADER, "rename").with(admin()));

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(412);
        assertThat(partners.lookupByCode("p1").orElseThrow().getDisplayName()).isEqualTo("One, renamed");
    }

    @Test
    @DisplayName("a time-boxed tenant grant is created; an unknown dimension is a 400; read-only support may not grant")
    void grants() throws Exception {
        String grant = "{\"subject\":\"bob\",\"principalType\":\"USER\",\"resourceType\":\"order\",\"action\":\"read\","
                + "\"dimension\":\"tenant\",\"dimensionValue\":\"acme\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}";

        MvcResult created = perform(json(post(BASE + "/grants"), grant)
                .header(AdminInvocation.REASON_HEADER, "INC-7").with(admin()));
        MvcResult badDimension = perform(json(post(BASE + "/grants"), grant.replace("\"tenant\"", "\"region\""))
                .header(AdminInvocation.REASON_HEADER, "INC-7").with(admin()));
        MvcResult bySupport = perform(json(post(BASE + "/grants"), grant)
                .header(AdminInvocation.REASON_HEADER, "INC-7").with(support()));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(badDimension.getResponse().getStatus()).isEqualTo(400);
        assertThat(bySupport.getResponse().getStatus()).isEqualTo(403);
        assertThat(grants.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a revoked grant keeps its row and is listed as lapsed")
    void revokeKeepsHistory() throws Exception {
        String id = JSON.readTree(perform(json(post(BASE + "/grants"),
                        "{\"subject\":\"bob\",\"principalType\":\"USER\","
                                + "\"resourceType\":\"order\",\"action\":\"read\"}")
                        .header(AdminInvocation.REASON_HEADER, "INC-7").with(admin())).getResponse()
                .getContentAsString()).path("id").asText();

        MvcResult revoked = perform(post(BASE + "/grants/" + id + "/revoke")
                .header(AdminInvocation.REASON_HEADER, "INC-7 closed").with(admin()));
        JsonNode lapsed = JSON.readTree(perform(get(BASE + "/grants").param("active", "false").with(support()))
                .getResponse().getContentAsString());

        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(grants.count()).isEqualTo(1);
        assertThat(lapsed.findValuesAsText("id")).containsExactly(id);
    }

    @Test
    @DisplayName("a subject's authorities are inspected uncached and audited; another tenant's subject is a 404")
    void inspection() throws Exception {
        MvcResult own = perform(get(BASE + "/subjects/bob/authorities").with(support()));
        MvcResult other = perform(get(BASE + "/subjects/gina/authorities").with(support()));

        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(own.getResponse().getContentAsString()).path("roles").toString())
                .contains("ROLE_ORDER_CLERK");
        assertThat(other.getResponse().getStatus()).isEqualTo(404);
        assertThat(adminEvents()).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("identity.read");
            assertThat(event.resource().id()).isEqualTo("bob");
        });
    }
}
