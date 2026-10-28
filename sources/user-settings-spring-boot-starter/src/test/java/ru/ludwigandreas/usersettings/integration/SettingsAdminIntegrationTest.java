package ru.ludwigandreas.usersettings.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.audit.store.entity.AuditEventEntity;
import ru.ludwigandreas.audit.store.repository.AuditEventRepository;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingsWriter;
import ru.ludwigandreas.usersettings.audit.SettingAuditAction;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;
import ru.ludwigandreas.usersettings.resolve.SettingsTenantResolver;

/**
 * The {@code settings} area through MVC dispatch and the administrative guard, over the real tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({UserSettingsTestConfiguration.class, PostgresContainerConfiguration.class,
        SettingsAdminIntegrationTest.TenantPlacement.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ludwig.user-settings.owner.enabled=true",
        "ludwig.user-settings.web.enabled=true",
        "ludwig.user-settings.default-tenant=acme",
        "ludwig.outbox.polling.enabled=false",
        "ludwig.outbox.default-route.transport=KAFKA",
        "ludwig.outbox.default-route.destination=user.settings",
        "ludwig.identity.enabled=false",
        "ludwig.security.jwt.enabled=false",
        "ludwig.admin.grants.settings.read[0]=SETTINGS_SUPPORT",
        "ludwig.admin.grants.settings.read[1]=SETTINGS_ADMIN",
        "ludwig.admin.grants.settings.operate[0]=SETTINGS_ADMIN",
        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class SettingsAdminIntegrationTest {

    private static final String SETTINGS = "/admin/v1/settings";

    /** A subject the deployment's resolver places in another tenant. */
    private static final String GLOBEX_USER = "globex-user";

    /**
     * A resolver that knows a subject's own tenant, as a deployment backed by its user directory would; the default
     * one answers with the caller's tenant for every subject, so a cross-tenant name never leaves the caller's data.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TenantPlacement {

        @Bean
        SettingsTenantResolver settingsTenantResolver() {
            return ref -> GLOBEX_USER.equals(ref.subject()) ? Optional.of("globex")
                    : SecurityPrincipals.current().flatMap(principal -> principal.tenant())
                            .or(() -> Optional.of("acme"));
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SettingsWriter writer;

    @Autowired
    private UserSettingValueRepository values;

    @Autowired
    private AuditEventRepository audit;

    @BeforeEach
    void reset() {
        values.deleteAll();
        audit.deleteAll();
    }

    private static RequestPostProcessor support() {
        return TestPrincipalBuilder.user("sam").tenant("acme").roles("SETTINGS_SUPPORT").postProcessor();
    }

    private static RequestPostProcessor admin() {
        return TestPrincipalBuilder.user("olga").tenant("acme").roles("SETTINGS_ADMIN").postProcessor();
    }

    @Test
    @DisplayName("support reads another subject's settings, and the read is audited")
    void readsAnotherSubject() throws Exception {
        writer.set(PrincipalRef.user("alice"), IntegrationSettings.PAGE_SIZE, 50);

        var response = mvc.perform(get(SETTINGS + "/subjects/alice").with(support())).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("ui.page-size").contains("50");
        List<AuditEventEntity> reads = audit.findAll().stream()
                .filter(event -> SettingAuditAction.ADMIN_READ.action().equals(event.getAction())).toList();
        assertThat(reads).hasSize(1);
        assertThat(reads.get(0).getActorSubject()).isEqualTo("sam");
        assertThat(reads.get(0).getOnBehalfOf()).isEqualTo("alice");
    }

    @Test
    @DisplayName("a subject the resolver places in another tenant is 404, not 403")
    void anotherTenantsSubjectIsNotFound() throws Exception {
        int status = mvc.perform(get(SETTINGS + "/subjects/" + GLOBEX_USER).with(support()))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }

    @Test
    @DisplayName("consent history and its point-in-time view are served on the new paths")
    void consents() throws Exception {
        int history = mvc.perform(get(SETTINGS + "/subjects/alice/consents").with(support()))
                .andReturn().getResponse().getStatus();
        int asOf = mvc.perform(get(SETTINGS + "/subjects/alice/consents/asof")
                        .param("at", "2026-01-01T00:00:00Z").with(support()))
                .andReturn().getResponse().getStatus();

        assertThat(history).isEqualTo(200);
        assertThat(asOf).isEqualTo(200);
    }

    @Test
    @DisplayName("a tenant-scope write needs settings.operate")
    void scopeWriteNeedsOperate() throws Exception {
        String body = "{\"values\":{\"ui.page-size\":\"25\"}}";

        int bySupport = mvc.perform(put(SETTINGS + "/scopes/TENANT/acme").contentType(MediaType.APPLICATION_JSON)
                .content(body).with(support())).andReturn().getResponse().getStatus();
        long afterRefusal = values.count();
        int byAdmin = mvc.perform(put(SETTINGS + "/scopes/TENANT/acme").contentType(MediaType.APPLICATION_JSON)
                .content(body).with(admin())).andReturn().getResponse().getStatus();

        assertThat(bySupport).isEqualTo(403);
        assertThat(afterRefusal).isZero();
        assertThat(byAdmin).isEqualTo(200);
        assertThat(values.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getScopeType()).isEqualTo(SettingLayer.TENANT));
    }

    @Test
    @DisplayName("the former paths are unmapped")
    void formerPathsAreUnmapped() throws Exception {
        int subject = mvc.perform(get("/admin/settings/subjects/alice").with(admin()))
                .andReturn().getResponse().getStatus();
        int asOf = mvc.perform(get(SETTINGS + "/subjects/alice/consents/as-of")
                        .param("at", "2026-01-01T00:00:00Z").with(admin()))
                .andReturn().getResponse().getStatus();

        assertThat(subject).isEqualTo(404);
        assertThat(asOf).isEqualTo(404);
    }
}
