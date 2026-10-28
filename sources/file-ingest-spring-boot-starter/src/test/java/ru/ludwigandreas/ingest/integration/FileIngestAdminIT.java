package ru.ludwigandreas.ingest.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The {@code fileingest} area through MVC dispatch and the administrative guard, over the real containers.
 */
@SpringBootTest(classes = IngestTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.enabled=true",
            "ludwig.security.jwt.enabled=false",
            "management.endpoints.web.exposure.include=fileingest",
            "ludwig.admin.grants.fileingest.read[0]=INGEST_SUPPORT",
            "ludwig.admin.grants.fileingest.read[1]=INGEST_ADMIN",
            "ludwig.admin.grants.fileingest.operate[0]=INGEST_ADMIN"})
@AutoConfigureMockMvc
class FileIngestAdminIT extends FileIngestTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TASKS = "/admin/v1/fileingest/tasks/";
    private static final String LIST = "/admin/v1/fileingest/tasks";

    @TempDir
    Path work;

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor admin() {
        return TestPrincipalBuilder.user("alice").roles("INGEST_ADMIN").postProcessor();
    }

    private static RequestPostProcessor support() {
        return TestPrincipalBuilder.user("sam").roles("INGEST_SUPPORT").postProcessor();
    }

    @Test
    @DisplayName("a trigger is a 202 pointing at the run, whose view carries checkpoint and counts but no rows")
    void triggerAndInspect() throws Exception {
        put(dropKey("catalogue-admin.csv"), writeCsv(work.resolve("admin.csv"), 5, 8));

        MvcResult triggered = mvc.perform(post(TASKS + CatalogueIngest.TASK + "/runs").with(admin())).andReturn();

        assertThat(triggered.getResponse().getStatus()).isEqualTo(202);
        String location = triggered.getResponse().getHeader("Location");
        assertThat(location).startsWith(TASKS + CatalogueIngest.TASK + "/runs/");
        assertThat(JSON.readTree(triggered.getResponse().getContentAsString()).path("status").asText())
                .isEqualTo("SUCCEEDED");

        String body = mvc.perform(get(location).with(support())).andReturn().getResponse().getContentAsString();
        JsonNode run = JSON.readTree(body);
        assertThat(run.path("checkpoint").path("recordsCommitted").asLong()).isEqualTo(5);
        assertThat(run.path("counts").path("applied").asLong()).isEqualTo(5);
        assertThat(body).doesNotContain("widget-");
    }

    @Test
    @DisplayName("support may look but not trigger; an unknown task is a 404")
    void grantsAndUnknownTask() throws Exception {
        assertThat(mvc.perform(get(LIST).with(support())).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post(TASKS + CatalogueIngest.TASK + "/runs").with(support())).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(TASKS + "no-such-task").with(support())).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("the actuator endpoint is gone even when its id is exposed")
    void actuatorEndpointIsGone() throws IOException, Exception {
        assertThat(mvc.perform(get("/actuator/fileingest").with(admin())).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }
}
