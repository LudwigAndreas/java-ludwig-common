package ru.ludwigandreas.webcore.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof of the claim: a request carrying a language and a zone is answered in both,
 * through a mapper-shaped conversion and through Spring's own holder, with the service having written
 * no resolver, no interceptor and no configuration.
 *
 * <p>{@link TestApplication} contains controllers and no configuration, so everything asserted here
 * is the starter's behaviour.
 */
@SpringBootTest(classes = {TestApplication.class, TestContributionConfiguration.class})
@AutoConfigureMockMvc
@ContextConfiguration
@TestPropertySource(properties = {
        "ludwig.web.i18n.supported-locales=en,ru",
        "ludwig.web.i18n.default-locale=en",
        "ludwig.web.preferences.default-zone=UTC"})
class UserPreferenceContextIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a request naming a language and a zone is answered in both")
    void headersReachTheMapper() throws Exception {
        mockMvc.perform(get("/preferences")
                        .header("Accept-Language", "ru-RU,ru;q=0.9")
                        .header("X-Time-Zone", "Asia/Yekaterinburg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("ru-RU"))
                .andExpect(jsonPath("$.zone").value("Asia/Yekaterinburg"))
                // The fixed instant is 2024-05-31T20:30:00Z, which is the 1st of June at +05:00. A
                // mapper that had rendered it in the container's zone would answer the 31st of May,
                // which is the off-by-a-day this contract exists to remove.
                .andExpect(jsonPath("$.mapped").value("2024-06-01T01:30:00+05:00"))
                .andExpect(jsonPath("$.mappedDate").value("2024-06-01"))
                .andExpect(jsonPath("$.mappedDecimal").value(org.hamcrest.Matchers.endsWith(",56")));
    }

    @Test
    @DisplayName("Spring's own LocaleContextHolder carries the zone, so existing code needs no edit")
    void theHolderCarriesTheZone() throws Exception {
        mockMvc.perform(get("/preferences")
                        .header("Accept-Language", "ru")
                        .header("X-Time-Zone", "Asia/Yekaterinburg"))
                .andExpect(status().isOk())
                // Before this contract, LocaleContextHolder.getTimeZone() answered TimeZone.getDefault()
                // here, because AcceptHeaderLocaleResolver publishes no timezone-aware context. This one
                // assertion is the whole reason the resolver took the localeResolver bean name rather
                // than being added as a filter beside it.
                .andExpect(jsonPath("$.holderZone").value("Asia/Yekaterinburg"));
    }

    @Test
    @DisplayName("an unsupported language gets the default in full, rather than a half-translated answer")
    void anUnsupportedLanguageFallsBackCompletely() throws Exception {
        mockMvc.perform(get("/preferences").header("Accept-Language", "de-DE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en"));
    }

    @Test
    @DisplayName("no headers at all yields the configured defaults, never the container's")
    void noHeadersYieldsTheConfiguredDefaults() throws Exception {
        mockMvc.perform(get("/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.zone").value("UTC"))
                .andExpect(jsonPath("$.mappedDate").value("2024-05-31"));
    }

    @Test
    @DisplayName("an unusable zone header is ignored rather than rejected")
    void anUnusableZoneHeaderIsIgnored() throws Exception {
        mockMvc.perform(get("/preferences").header("X-Time-Zone", "Mars/Olympus_Mons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zone").value("UTC"));
    }
}
