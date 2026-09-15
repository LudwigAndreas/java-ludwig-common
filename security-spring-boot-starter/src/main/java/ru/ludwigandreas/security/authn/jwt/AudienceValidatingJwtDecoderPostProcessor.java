package ru.ludwigandreas.security.authn.jwt;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Adds the {@link AudienceValidator} to the {@code JwtDecoder} Spring Boot built from
 * {@code issuer-uri}, without this module having to take over decoder construction.
 *
 * <p>Replacing the decoder outright would mean re-implementing JWKS fetching, key rotation and caching -
 * all of which Boot already does well - and would silently drop any decoder customization the service
 * made. Decorating the validator instead keeps Boot's decoder and adds exactly the one check that
 * would otherwise be missing.
 *
 * <p>The default validators (issuer, expiry, not-before) are kept and the audience check is appended;
 * {@code setJwtValidator} replaces the whole chain, so composing explicitly here is what stops the
 * audience check from quietly disabling expiry validation.
 */
@Slf4j
@RequiredArgsConstructor
public class AudienceValidatingJwtDecoderPostProcessor implements BeanPostProcessor {

    private final String issuerUri;
    private final Set<String> audiences;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (audiences.isEmpty() || !(bean instanceof NimbusJwtDecoder decoder)) {
            return bean;
        }
        OAuth2TokenValidator<Jwt> defaults = issuerUri == null || issuerUri.isBlank()
                ? JwtValidators.createDefault()
                : JwtValidators.createDefaultWithIssuer(issuerUri);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaults, new AudienceValidator(audiences)));
        log.info("JWT audience validation enabled for {}", audiences);
        return bean;
    }
}
