package ru.ludwigandreas.security.authn.jwt;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Adds the {@link AudienceValidator} to the application's {@code JwtDecoder}, whatever built it,
 * without this module having to take over decoder construction.
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

    /**
     * Installs the audience check on whatever decoder the application ends up with.
     *
     * <p>A {@code NimbusJwtDecoder} is configured in place, which also keeps its issuer check. Any
     * other decoder is wrapped. That second case is not an edge: with
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, which is how a deployed service is
     * configured, Spring Boot's decoder bean is a lazy {@code SupplierJwtDecoder} whose Nimbus
     * delegate is private and not built until the first token arrives. Recognising only the Nimbus
     * type meant the check was skipped for exactly the configuration every service runs with, and a
     * token the same issuer minted for another service was accepted.
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (audiences.isEmpty() || !(bean instanceof JwtDecoder)) {
            return bean;
        }
        AudienceValidator audienceValidator = new AudienceValidator(audiences);
        if (bean instanceof NimbusJwtDecoder decoder) {
            OAuth2TokenValidator<Jwt> defaults = issuerUri == null || issuerUri.isBlank()
                    ? JwtValidators.createDefault()
                    : JwtValidators.createDefaultWithIssuer(issuerUri);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaults, audienceValidator));
            log.info("JWT audience validation enabled for {}", audiences);
            return bean;
        }
        log.info("JWT audience validation enabled for {}", audiences);
        return new AudienceCheckingJwtDecoder((JwtDecoder) bean, audienceValidator);
    }

    /**
     * Checks the audience of a token another decoder has already verified.
     *
     * <p>The delegate has done everything else by the time this runs - signature, expiry, issuer - so
     * a failure here is reported the same way those are, as a validation failure, and reaches the
     * caller as the same 401.
     */
    private static final class AudienceCheckingJwtDecoder implements JwtDecoder {

        private final JwtDecoder delegate;
        private final AudienceValidator audienceValidator;

        private AudienceCheckingJwtDecoder(JwtDecoder delegate, AudienceValidator audienceValidator) {
            this.delegate = delegate;
            this.audienceValidator = audienceValidator;
        }

        @Override
        public Jwt decode(String token) {
            Jwt jwt = delegate.decode(token);
            OAuth2TokenValidatorResult result = audienceValidator.validate(jwt);
            if (result.hasErrors()) {
                throw new JwtValidationException(
                        "The token's audience does not include this service", result.getErrors());
            }
            return jwt;
        }
    }
}
