package ru.ludwigandreas.testsupport.security;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Makes every {@code MockMvc} request in a test class run as one default caller unless it says otherwise.
 *
 * <h2>Why this is separate from {@link RejectingJwtDecoderConfiguration}, and optional</h2>
 *
 * <p>Both services needed the rejecting decoder. Only {@code crud-service-example} defaulted its
 * requests to an admin caller; {@code notification-service} names a caller on every request on purpose,
 * because several of its tests are precisely about what a caller <em>without</em> a role is refused.
 * Bundling the default into the decoder configuration would have quietly changed those tests from
 * "anonymous is refused" to "an admin is allowed", which is a test still passing while no longer
 * asserting anything.
 *
 * <p>So it is opt-in, and a service that opts in states which caller it defaults to - this class holds
 * no opinion about which role that is, for the same reason {@link TestPrincipalBuilder} holds no
 * domain vocabulary.
 *
 * <h2>Precedence</h2>
 *
 * <p>A default request is applied first and a request's own {@code .with(...)} is applied after it, so a
 * test that names a narrower caller wins. That ordering is what makes the default safe to use at all.
 *
 * <h2>Use</h2>
 *
 * <pre>{@code
 * @TestConfiguration(proxyBeanMethods = false)
 * class TestSecurityConfiguration {
 *
 *     @Bean
 *     MockMvcBuilderCustomizer defaultCaller() {
 *         return DefaultCallerConfiguration.defaultCaller(TestPrincipals.admin());
 *     }
 * }
 * }</pre>
 */
public final class DefaultCallerConfiguration {

    private DefaultCallerConfiguration() {
    }

    /**
     * A customizer that runs every request as {@code caller} unless the request overrides it.
     *
     * @param caller the post-processor for the default caller, from {@link TestPrincipalBuilder}
     * @return a customizer to expose as a {@code @Bean}
     */
    public static MockMvcBuilderCustomizer defaultCaller(RequestPostProcessor caller) {
        return builder -> builder.defaultRequest(
                MockMvcRequestBuilders.get("/").with(caller));
    }
}
