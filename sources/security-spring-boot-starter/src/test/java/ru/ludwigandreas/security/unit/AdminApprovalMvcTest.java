package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminApprovals;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * Four-eyes approval through real MVC dispatch: a privileged call is held, a different holder approves it, and the
 * very same handler then runs as the approver.
 */
@SpringBootTest(classes = AdminApprovalMvcTest.Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.jwt.enabled=false", "ludwig.web.i18n.configure-validator=false",
            "ludwig.admin.grants.fixture.read[0]=SUPPORT",
            "ludwig.admin.grants.fixture.read[1]=ADMIN",
            "ludwig.admin.grants.fixture.operate[0]=ADMIN",
            "ludwig.admin.grants.fixture.privileged[0]=ADMIN",
            "ludwig.admin.approval.required[0]=fixture.privileged"})
@AutoConfigureMockMvc
class AdminApprovalMvcTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();
    private static final Map<String, String> MESSAGES = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> ROLES = new ConcurrentHashMap<>();
    private static final MutableClock CLOCK = new MutableClock();

    /** A clock a test can move forward. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-29T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A module's privileged handler, unaware of approval. */
    @RestController
    static class MessagesController {

        private final AdminAudit audit;

        MessagesController(AdminAudit audit) {
            this.audit = audit;
        }

        @PostMapping("/admin/v1/fixture/messages/{id}/discard")
        @AdminAccess("fixture.privileged")
        public Map<String, String> discard(@PathVariable String id, @RequestBody Map<String, String> body) {
            MESSAGES.put(id, "DISCARDED");
            audit.record("fixture-message", id, AdminOutcome.SUCCEEDED, Map.of("note", body.get("note")));
            return Map.of("id", id, "state", "DISCARDED", "by", AdminInvocation.require().principal().subject());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {

        @Bean
        AdminOperationContributor fixtureOperations() {
            return () -> List.of(
                    AdminOperation.of("fixture.read", "test.fixture.read"),
                    AdminOperation.of("fixture.operate", "test.fixture.operate"),
                    AdminOperation.of("fixture.privileged", "test.fixture.privileged"));
        }

        @Bean
        ProblemMessageBundle adminFixtureMessages() {
            return ProblemMessageBundle.of("i18n/admin-fixture-messages");
        }

        @Bean
        MessagesController messagesController(AdminAudit audit) {
            return new MessagesController(audit);
        }

        @Bean
        InMemoryAdminApprovalStore approvalStore() {
            return new InMemoryAdminApprovalStore();
        }

        @Bean
        AuditSink capturingAuditSink() {
            return AUDIT::add;
        }

        @Bean
        Clock approvalClock() {
            return CLOCK;
        }

        /** The live authority lookup the requester is re-checked through at approval. */
        @Bean
        AuthorityLookup authorityLookup() {
            return ref -> Authorities.builder().roles(ROLES.getOrDefault(ref.subject(), Set.of())).build();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AdminApprovals approvals;

    @Autowired
    private InMemoryAdminApprovalStore store;

    @BeforeEach
    void reset() {
        store.clear();
        AUDIT.clear();
        MESSAGES.put("m-1", "FAILED");
        ROLES.put("alice", Set.of("ADMIN"));
        ROLES.put("bob", Set.of("ADMIN"));
        ROLES.put("sam", Set.of("SUPPORT"));
    }

    private static RequestPostProcessor as(String subject) {
        return authentication(new LudwigAuthentication(LudwigPrincipal.builder().subject(subject)
                .type(PrincipalType.USER).roles(ROLES.get(subject)).build()));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult requestDiscard(String subject) throws Exception {
        return perform(post("/admin/v1/fixture/messages/m-1/discard").contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"poison\"}").header(AdminInvocation.REASON_HEADER, "INC-7").with(as(subject)));
    }

    private String pendingApproval() throws Exception {
        MvcResult held = requestDiscard("alice");
        assertThat(held.getResponse().getStatus()).as(held.getResponse().getContentAsString()).isEqualTo(202);
        return json(held).path("id").asText();
    }

    private List<AuditEvent> withApproval(String action) {
        return AUDIT.stream().filter(event -> action.equals(event.attributes().get("approval"))).toList();
    }

    @Test
    @DisplayName("a call needing approval is recorded and answered 202 with a pending approval; nothing runs")
    void heldNotExecuted() throws Exception {
        MvcResult held = requestDiscard("alice");

        assertThat(held.getResponse().getStatus()).isEqualTo(202);
        JsonNode approval = json(held);
        assertThat(held.getResponse().getHeader("Location"))
                .isEqualTo("/admin/v1/approvals/" + approval.path("id").asText());
        assertThat(approval.path("operationEnvelope").path("status").asText()).isEqualTo("PENDING");
        assertThat(approval.path("operation").asText()).isEqualTo("fixture.privileged");
        assertThat(approval.path("requestedBy").asText()).isEqualTo("alice");
        assertThat(approval.path("body").isNull()).isTrue();
        assertThat(MESSAGES).containsEntry("m-1", "FAILED");
        assertThat(withApproval("requested")).singleElement().satisfies(event -> {
            assertThat(event.actor().subject()).isEqualTo("alice");
            assertThat(event.attributes()).containsEntry("reason", "INC-7");
        });
    }

    @Test
    @DisplayName("a call refused without a reason, or by a caller without the operation, records no approval")
    void refusedCallsAreNotHeld() throws Exception {
        assertThat(perform(post("/admin/v1/fixture/messages/m-1/discard").contentType(MediaType.APPLICATION_JSON)
                .content("{}").with(as("alice"))).getResponse().getStatus()).isEqualTo(400);
        assertThat(requestDiscard("sam").getResponse().getStatus()).isEqualTo(403);
        assertThat(withApproval("requested")).isEmpty();
    }

    @Test
    @DisplayName("a body over 64 KiB is refused with 413 and records no approval")
    void largeBody() throws Exception {
        String big = "{\"note\":\"" + "x".repeat(65 * 1024) + "\"}";
        assertThat(perform(post("/admin/v1/fixture/messages/m-1/discard").contentType(MediaType.APPLICATION_JSON)
                .content(big).header(AdminInvocation.REASON_HEADER, "INC-7").with(as("alice")))
                .getResponse().getStatus()).isEqualTo(413);
        assertThat(withApproval("requested")).isEmpty();
    }

    @Test
    @DisplayName("bob approves: the same handler runs as bob, the approval SUCCEEDED, and the audit names both")
    void peerApproves() throws Exception {
        String id = pendingApproval();

        MvcResult approved = perform(post("/admin/v1/approvals/" + id + "/approve").with(as("bob")));

        assertThat(approved.getResponse().getStatus()).as(approved.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode approval = json(approved);
        assertThat(approval.path("state").asText()).isEqualTo("EXECUTED");
        assertThat(approval.path("operationEnvelope").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(approval.path("outcome").path("status").asInt()).isEqualTo(200);
        assertThat(JSON.readTree(approval.path("outcome").path("body").asText()).path("by").asText())
                .isEqualTo("bob");
        assertThat(approved.getResponse().getHeader("Location")).isNull();
        assertThat(MESSAGES).containsEntry("m-1", "DISCARDED");
        assertThat(AUDIT).filteredOn(event -> "fixture-message".equals(event.resource().type())).singleElement()
                .satisfies(event -> assertThat(event.attributes()).containsEntry("requestedBy", "alice")
                        .containsEntry("approvedBy", "bob").containsEntry("note", "poison")
                        .containsEntry("reason", "INC-7"));
        assertThat(withApproval("approved")).singleElement()
                .satisfies(event -> assertThat(event.actor().subject()).isEqualTo("bob"));
        assertThat(perform(post("/admin/v1/approvals/" + id + "/approve").with(as("bob"))).getResponse()
                .getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("alice cannot approve her own request; it stays pending")
    void selfApproval() throws Exception {
        String id = pendingApproval();

        assertThat(perform(post("/admin/v1/approvals/" + id + "/approve").with(as("alice"))).getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(json(perform(get("/admin/v1/approvals/" + id).with(as("bob")))).path("state").asText())
                .isEqualTo("PENDING");
        assertThat(MESSAGES).containsEntry("m-1", "FAILED");
    }

    @Test
    @DisplayName("a requester who lost the operation meanwhile: 409, nothing executed, still pending")
    void requesterLostTheOperation() throws Exception {
        String id = pendingApproval();
        ROLES.put("alice", Set.of("SUPPORT"));

        assertThat(perform(post("/admin/v1/approvals/" + id + "/approve").with(as("bob"))).getResponse()
                .getStatus()).isEqualTo(409);
        assertThat(MESSAGES).containsEntry("m-1", "FAILED");
        assertThat(json(perform(get("/admin/v1/approvals/" + id).with(as("bob")))).path("state").asText())
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the list shows only approvals of operations the caller holds; the body only to a decider")
    void visibility() throws Exception {
        String id = pendingApproval();

        assertThat(json(perform(get("/admin/v1/approvals").with(as("sam")))).path("items")).isEmpty();
        assertThat(json(perform(get("/admin/v1/approvals").param("status", "PENDING").with(as("bob"))))
                .path("items").findValuesAsText("id")).contains(id);
        assertThat(json(perform(get("/admin/v1/approvals").with(as("bob")))).path("items").get(0).path("body")
                .isNull()).isTrue();
        assertThat(json(perform(get("/admin/v1/approvals/" + id).with(as("bob")))).path("body").asText())
                .contains("poison");
        assertThat(perform(get("/admin/v1/approvals/" + id).with(as("sam"))).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("undecided for 24 hours, an approval expires, cannot be approved, and the expiry is audited")
    void expiry() throws Exception {
        String id = pendingApproval();
        CLOCK.advance(Duration.ofHours(25));

        assertThat(approvals.sweep()).isEqualTo(1);
        assertThat(json(perform(get("/admin/v1/approvals/" + id).with(as("bob")))).path("operationEnvelope")
                .path("status").asText()).isEqualTo("EXPIRED");
        assertThat(perform(post("/admin/v1/approvals/" + id + "/approve").with(as("bob"))).getResponse()
                .getStatus()).isEqualTo(409);
        assertThat(withApproval("expired")).singleElement()
                .satisfies(event -> assertThat(event.actor().subject()).isEqualTo("system"));
        assertThat(MESSAGES).containsEntry("m-1", "FAILED");
    }

    @Test
    @DisplayName("bob rejects, alice withdraws; only the requester may withdraw; both end CANCELLED")
    void rejectAndWithdraw() throws Exception {
        String rejected = pendingApproval();
        String withdrawn = pendingApproval();

        assertThat(json(perform(post("/admin/v1/approvals/" + rejected + "/reject").with(as("bob"))))
                .path("operationEnvelope").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(perform(post("/admin/v1/approvals/" + withdrawn + "/withdraw").with(as("bob"))).getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(json(perform(post("/admin/v1/approvals/" + withdrawn + "/withdraw").with(as("alice"))))
                .path("state").asText()).isEqualTo("WITHDRAWN");
        assertThat(withApproval("rejected")).hasSize(1);
        assertThat(withApproval("withdrawn")).hasSize(1);
        assertThat(MESSAGES).containsEntry("m-1", "FAILED");
    }
}
