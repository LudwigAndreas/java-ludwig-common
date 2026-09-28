package ru.ludwigandreas.webcore.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The starter's advice must be the fallback, not the first responder.
 *
 * <p>It handles {@code Exception}, so if it were consulted before a service's own advice, that
 * service's specific {@code @ExceptionHandler} would silently never run - Spring returns the first
 * advice that can handle the exception at all, and "can handle {@code Exception}" always can. Hence
 * the default order of {@code Ordered.LOWEST_PRECEDENCE}: every other advice gets first refusal.
 */
@SpringBootTest(classes = {TestApplication.class, AdviceOrderingTest.ServiceOwnAdvice.class})
@AutoConfigureMockMvc
class AdviceOrderingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a service's own advice handles a type the starter's catch-all would also match")
    void serviceAdviceWinsOverTheStartersCatchAll() {
        // /test/boom throws IllegalStateException, which the starter would answer as a 500.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                mockMvc.perform(get("/test/boom"))
                        .andExpect(status().isIAmATeapot())
                        .andExpect(jsonPath("$.code").value("service.own.advice")));
    }

    @Test
    @DisplayName("the starter still answers everything the service's advice does not claim")
    void starterStillHandlesTheRest() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                mockMvc.perform(get("/test/business"))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("error.thing.not-found")));
    }

    /**
     * What a service writes when it wants one exception type rendered its own way - deliberately
     * with no {@code @Order}, because that is what people write, and it is the case an advice
     * registered anywhere but last would break.
     */
    @TestConfiguration
    @RestControllerAdvice
    static class ServiceOwnAdvice {

        @ExceptionHandler(IllegalStateException.class)
        public ProblemDetail handle(IllegalStateException exception) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.I_AM_A_TEAPOT);
            problem.setProperty("code", "service.own.advice");
            return problem;
        }
    }
}
