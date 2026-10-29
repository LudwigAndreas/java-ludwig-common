package ru.ludwigandreas.job.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Function;
import javax.sql.DataSource;
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
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.job.core.control.ReplicaControlPoller;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The {@code caches} area: listing the registered caches, and clearing one on every replica through the
 * {@code cacheevict} control, with the tier depending on the cache's purpose.
 */
@SpringBootTest(classes = CacheAdminIT.Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.jwt.enabled=false",
            "ludwig.web.i18n.configure-validator=false",
            "spring.liquibase.change-log=classpath:db/changelog/job-core/job-core-changelog.xml",
            "ludwig.job-core.owner=cache-replica",
            "ludwig.job-core.control.poll-interval=PT1H",
            "ludwig.admin.grants.replicas.read[0]=OPS",
            "ludwig.admin.grants.caches.read[0]=OPS",
            "ludwig.admin.grants.caches.operate[0]=OPS",
            "ludwig.admin.grants.caches.privileged[0]=SECURITY_ADMIN"})
@AutoConfigureMockMvc
class CacheAdminIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final CacheDefinition<String, String> PROFILES = CacheDefinition.<String, String>named("test-profiles",
            CachePurpose.PERFORMANCE).owner("job-core-tests").defaultTtl(Duration.ofMinutes(5))
            .valueType(String.class).keyRenderer(Function.identity()).build();

    static final CacheDefinition<String, String> GRANTS = CacheDefinition.<String, String>named("test-grants",
            CachePurpose.SECURITY).owner("job-core-tests").defaultTtl(Duration.ofSeconds(30))
            .valueType(String.class).keyRenderer(Function.identity()).build();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {

        @Bean
        DataSource dataSource() {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setUrl(Containers.postgres().getJdbcUrl());
            source.setUser(Containers.postgres().getUsername());
            source.setPassword(Containers.postgres().getPassword());
            return source;
        }

        @Bean
        CacheDefinition<String, String> profilesCache() {
            return PROFILES;
        }

        @Bean
        CacheDefinition<String, String> grantsCache() {
            return GRANTS;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LudwigCacheRegistry registry;

    @Autowired
    private ReplicaControlPoller replica;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void reset() {
        new JdbcTemplate(dataSource).execute("DELETE FROM job_control_command; DELETE FROM job_instance");
        replica.runNow();
    }

    private static RequestPostProcessor ops() {
        return TestPrincipalBuilder.user("olga").roles("OPS").postProcessor();
    }

    private static RequestPostProcessor securityAdmin() {
        return TestPrincipalBuilder.user("sol").roles("OPS", "SECURITY_ADMIN").postProcessor();
    }

    private MvcResult evict(String cache, RequestPostProcessor caller, String reason) throws Exception {
        var request = post("/admin/v1/replicas/controls/cacheevict").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cache\":\"" + cache + "\"}").with(caller);
        return mvc.perform(reason == null ? request : request.header(AdminInvocation.REASON_HEADER, reason))
                .andReturn();
    }

    @Test
    @DisplayName("the registered caches are listed with purpose and TTL, and no value")
    void listsCaches() throws Exception {
        registry.cache(PROFILES).put("k", "secret-value");

        String body = mvc.perform(get("/admin/v1/caches").with(ops())).andReturn().getResponse()
                .getContentAsString();

        JsonNode caches = JSON.readTree(body);
        assertThat(caches.findValuesAsText("name")).contains("test-profiles", "test-grants");
        assertThat(body).contains("\"purpose\":\"PERFORMANCE\"").contains("\"purpose\":\"SECURITY\"")
                .doesNotContain("secret-value");
    }

    @Test
    @DisplayName("clearing a performance cache everywhere: the command succeeds and the next read misses")
    void clearsAPerformanceCache() throws Exception {
        LudwigCache<String, String> cache = registry.cache(PROFILES);
        cache.put("k", "v");

        MvcResult submitted = evict("test-profiles", ops(), null);
        replica.runNow();

        assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString())
                .isEqualTo(202);
        JsonNode command = JSON.readTree(mvc.perform(get(submitted.getResponse().getHeader("Location")).with(ops()))
                .andReturn().getResponse().getContentAsString());
        assertThat(command.path("operationEnvelope").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(cache.get("k")).isEmpty();
    }

    @Test
    @DisplayName("a security cache needs caches.privileged and a reason; an unknown cache is a 400")
    void securityCacheIsPrivileged() throws Exception {
        assertThat(evict("test-grants", ops(), "INC-3").getResponse().getStatus()).isEqualTo(403);
        assertThat(evict("test-grants", securityAdmin(), null).getResponse().getStatus()).isEqualTo(400);
        assertThat(evict("test-grants", securityAdmin(), "INC-3").getResponse().getStatus()).isEqualTo(202);
        assertThat(evict("no-such-cache", ops(), null).getResponse().getStatus()).isEqualTo(400);
    }
}
