package ru.ludwigandreas.notification.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.testsupport.security.RejectingJwtDecoderConfiguration;

/**
 * Test-only wiring for the one thing the security stack needs from outside the process: a
 * {@link org.springframework.security.oauth2.jwt.JwtDecoder} that does not perform OIDC discovery.
 *
 * <p>{@link RejectingJwtDecoderConfiguration} supplies it and records the reasoning - that the decoder
 * Boot builds from {@code issuer-uri} would have to reach the identity provider just to start the
 * context, and that rejecting every token is correct because these tests authenticate by injecting a
 * principal, so a decoder that silently accepted anything would hide a misconfiguration.
 *
 * <p>Deliberately no {@code MockMvcBuilderCustomizer} defaulting requests to an admin: several tests
 * here assert what a caller <em>without</em> a role is refused, and a default caller would turn those
 * into tests that pass while asserting nothing.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(RejectingJwtDecoderConfiguration.class)
public class TestSecurityConfiguration {
}
