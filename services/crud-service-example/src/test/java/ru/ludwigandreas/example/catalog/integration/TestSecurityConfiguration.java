package ru.ludwigandreas.example.catalog.integration;

import static ru.ludwigandreas.example.catalog.integration.TestPrincipals.admin;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.testsupport.security.DefaultCallerConfiguration;
import ru.ludwigandreas.testsupport.security.RejectingJwtDecoderConfiguration;

/**
 * Test-only wiring for the two things the security stack needs from outside the process.
 *
 * <p>The {@code JwtDecoder} that rejects every token comes from
 * {@link RejectingJwtDecoderConfiguration}, which records why rejecting is the correct behaviour rather
 * than accepting, and why the context could not start without it.
 *
 * <p>The default caller stays here because it is this service's choice: only this service defaults its
 * requests to an admin, and which role that is is catalog vocabulary.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(RejectingJwtDecoderConfiguration.class)
public class TestSecurityConfiguration {

    /**
     * Runs every request as the admin unless the request says otherwise. Tests that care about a
     * narrower caller add their own {@code .with(...)}, which is applied after this default and wins.
     *
     * @return the customizer
     */
    @Bean
    public MockMvcBuilderCustomizer defaultCaller() {
        return DefaultCallerConfiguration.defaultCaller(admin());
    }
}
