package ru.ludwigandreas.webcore.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof of the claim the starter makes: add the dependency, and every failure - business,
 * validation, framework, security, unhandled - comes back as one RFC 9457 document in the caller's
 * language, with no advice, no {@code MessageSource} and no locale resolver written by the service.
 *
 * <p>{@link TestApplication} contains exactly one controller and no configuration, so anything
 * asserted here is the starter's behaviour rather than the test's.
 */
@SpringBootTest(classes = {TestApplication.class, TestContributionConfiguration.class})
@AutoConfigureMockMvc
@ContextConfiguration
class ProblemDetailIntegrationTest {

    private static final String RUSSIAN = "ru-RU,ru;q=0.9";

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("a service's own business failures")
    class BusinessFailures {

        @Test
        @DisplayName("render as a localized problem with the service's own code")
        void rendersBusinessFailure() throws Exception {
            mockMvc.perform(get("/test/business"))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                            MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                    .andExpect(jsonPath("$.code").value("error.thing.not-found"))
                    .andExpect(jsonPath("$.title").value("Thing not found"))
                    .andExpect(jsonPath("$.detail")
                            .value("No thing exists with id 00000000-0000-0000-0000-0000000000ff."))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.type").value("urn:ludwig:problem:error.thing.not-found"))
                    .andExpect(jsonPath("$.instance").value("/test/business"));
        }

        @Test
        @DisplayName("answer in the caller's language")
        void translatesToRequestLocale() throws Exception {
            mockMvc.perform(get("/test/business").header(HttpHeaders.ACCEPT_LANGUAGE, RUSSIAN))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "ru"))
                    .andExpect(jsonPath("$.title").value("Вещь не найдена"))
                    .andExpect(jsonPath("$.detail")
                            .value("Вещь с идентификатором 00000000-0000-0000-0000-0000000000ff не существует."));
        }

        @Test
        @DisplayName("fall back to the default language, in full, for an unsupported one")
        void fallsBackToDefaultLocale() throws Exception {
            // Not a half-translated response, and not the server host's locale either.
            mockMvc.perform(get("/test/business").header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR"))
                    .andExpect(jsonPath("$.title").value("Thing not found"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en"));
        }

        @Test
        @DisplayName("survive being wrapped by a framework layer on the way out")
        void unwrapsWrappedBusinessFailure() throws Exception {
            // Without the cause-chain walk this is a 500 - a deliberate 404 lost to a proxy.
            mockMvc.perform(get("/test/wrapped"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("error.thing.not-found"));
        }

        @Test
        @DisplayName("carry the trace id, so the message asking for it is actionable")
        void publishesTraceId() throws Exception {
            mockMvc.perform(get("/test/business"))
                    .andExpect(jsonPath("$.traceId").value(TestContributionConfiguration.TRACE_ID))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("reports a rejected body field by field, with localized messages")
        void reportsBodyViolations() throws Exception {
            mockMvc.perform(post("/test/things")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\",\"quantity\":-1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.validation"))
                    .andExpect(jsonPath("$.title").value("Invalid request"))
                    .andExpect(jsonPath("$.violations[?(@.field == 'name')].message")
                            .value("A name is required."))
                    .andExpect(jsonPath("$.violations[?(@.field == 'quantity')].message")
                            .value("The quantity must not be negative."))
                    .andExpect(jsonPath("$.violations[?(@.field == 'name')].code").value("NotBlank"));
        }

        @Test
        @DisplayName("translates the field messages too, not just the title")
        void translatesViolationMessages() throws Exception {
            // The half that silently stays English when the validator is not wired to the
            // application's MessageSource - which the starter does for the service.
            mockMvc.perform(post("/test/things")
                            .header(HttpHeaders.ACCEPT_LANGUAGE, RUSSIAN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\",\"quantity\":0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Некорректный запрос"))
                    .andExpect(jsonPath("$.violations[?(@.field == 'name')].message")
                            .value("Название обязательно."));
        }

        @Test
        @DisplayName("never echoes the submitted value back by default")
        void hidesRejectedValue() throws Exception {
            mockMvc.perform(post("/test/things")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"far too long to be valid\",\"quantity\":1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations[0].rejectedValue").doesNotExist());
        }

        @Test
        @DisplayName("reports a failed constraint on a request parameter in the same shape")
        void reportsParameterViolations() throws Exception {
            mockMvc.perform(get("/test/search").param("size", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.validation"))
                    .andExpect(jsonPath("$.violations").isArray())
                    // Named, not reported as "arg0": the build keeps parameter names - see the
                    // -parameters compiler flag in the root pom.
                    .andExpect(jsonPath("$.violations[0].field").value("size"))
                    .andExpect(jsonPath("$.violations[0].message").exists());
        }
    }

    @Nested
    @DisplayName("Spring MVC's own failures")
    class FrameworkFailures {

        @Test
        @DisplayName("a wrong method is a localized 405 that keeps its Allow header")
        void rendersMethodNotAllowed() throws Exception {
            mockMvc.perform(post("/test/business"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().exists(HttpHeaders.ALLOW))
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.method-not-allowed"))
                    .andExpect(jsonPath("$.detail").value(
                            "This resource does not support POST requests. "
                                    + "See the Allow header for the methods it does support."))
                    .andExpect(jsonPath("$.method").value("POST"));
        }

        @Test
        @DisplayName("a wrong content type is a localized 415")
        void rendersUnsupportedMediaType() throws Exception {
            mockMvc.perform(post("/test/things").contentType(MediaType.TEXT_PLAIN).content("nope"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.unsupported-media-type"))
                    .andExpect(jsonPath("$.supportedMediaTypes").isArray());
        }

        @Test
        @DisplayName("an unparseable body is a 400 that does not quote the parser")
        void rendersMalformedBody() throws Exception {
            mockMvc.perform(post("/test/things")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.malformed-request"))
                    .andExpect(jsonPath("$.detail").value("The request body could not be parsed."));
        }

        @Test
        @DisplayName("a missing parameter names the parameter in a member, not in the sentence")
        void rendersMissingParameter() throws Exception {
            mockMvc.perform(get("/test/search"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.bad-request"))
                    .andExpect(jsonPath("$.parameter").value("size"));
        }

        @Test
        @DisplayName("an unconvertible parameter is a 400, not a 500")
        void rendersTypeMismatch() throws Exception {
            mockMvc.perform(get("/test/search").param("size", "not-a-number"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.bad-request"))
                    .andExpect(jsonPath("$.parameter").value("size"));
        }

        @Test
        @DisplayName("an unknown path is a localized 404 in the same shape as every other error")
        void rendersUnknownPath() throws Exception {
            mockMvc.perform(get("/test/no-such-endpoint").header(HttpHeaders.ACCEPT_LANGUAGE, RUSSIAN))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.not-found"))
                    .andExpect(jsonPath("$.title").value("Не найдено"));
        }
    }

    @Nested
    @DisplayName("security failures that reach a controller")
    class SecurityFailures {

        @Test
        @DisplayName("an authorization failure inside the dispatch is a 403, not a 500")
        void rendersAccessDenied() throws Exception {
            // @PreAuthorize and data guards throw after the filter chain has handed over, where the
            // application's AccessDeniedHandler can no longer see them.
            mockMvc.perform(get("/test/denied"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.forbidden"));
        }

        @Test
        @DisplayName("the application's bundle overrides a message the starter ships")
        void applicationOverridesStarterMessage() throws Exception {
            mockMvc.perform(get("/test/denied"))
                    .andExpect(jsonPath("$.detail").value("This operation is not available on your plan."));
            mockMvc.perform(get("/test/denied").header(HttpHeaders.ACCEPT_LANGUAGE, RUSSIAN))
                    .andExpect(jsonPath("$.detail").value("Операция недоступна на вашем тарифе."));
        }

        @Test
        @DisplayName("a 401 says only that authentication is needed")
        void rendersUnauthenticated() throws Exception {
            mockMvc.perform(get("/test/unauthenticated"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.unauthorized"))
                    .andExpect(jsonPath("$.detail")
                            .value("Authentication is required to access this resource."))
                    // "token expired at 12:03" is useful to an attacker and useless to a client.
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("12:03"))));
        }
    }

    @Nested
    @DisplayName("data-access failures")
    class DataAccessFailures {

        @Test
        @DisplayName("a constraint violation is a 409 rather than a 500")
        void rendersIntegrityViolation() throws Exception {
            mockMvc.perform(get("/test/duplicate"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.conflict"));
        }

        @Test
        @DisplayName("losing an optimistic-locking race is the same 409 a stale version gets")
        void rendersOptimisticLockingFailure() throws Exception {
            mockMvc.perform(get("/test/stale"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.concurrent-modification"));
        }
    }

    @Nested
    @DisplayName("extension and last resort")
    class ExtensionAndLastResort {

        @Test
        @DisplayName("an application's contributed mapper renders an exception the starter never knew")
        void rendersContributedMapping() throws Exception {
            mockMvc.perform(get("/test/quota").header(HttpHeaders.ACCEPT_LANGUAGE, RUSSIAN))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("error.quota.exceeded"))
                    .andExpect(jsonPath("$.detail").value("Можно создать не более 50 вещей."))
                    .andExpect(jsonPath("$.limit").value(50));
        }

        @Test
        @DisplayName("an unhandled fault is a 500 that leaks nothing about it")
        void rendersUnhandledFault() throws Exception {
            mockMvc.perform(get("/test/boom"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("ludwig.web.error.internal"))
                    .andExpect(jsonPath("$.traceId").value(TestContributionConfiguration.TRACE_ID))
                    // The host name in the exception's message must not reach the client.
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("db-7.internal"))))
                    .andExpect(jsonPath("$.debug").doesNotExist());
        }

        @Test
        @DisplayName("a successful response is untouched, and pages are the envelope's shape")
        void leavesSuccessfulResponsesAlone() throws Exception {
            mockMvc.perform(get("/test/ok"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.pageable").doesNotExist());
        }
    }
}
