package ru.ludwigandreas.security.authn.jwt;

import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * Rejects a token that was not issued for this service.
 *
 * <p>Signature and issuer checks alone do not do this. In a mesh where every service trusts the same
 * OIDC provider, a token minted for service A is perfectly valid at service B - so anything that can
 * obtain a token for the least sensitive service can replay it against the most sensitive one. The
 * audience claim is what makes a token service-specific, and checking it is not optional.
 *
 * <p>Spring Boot's {@code spring.security.oauth2.resourceserver.jwt.audiences} covers the common case;
 * this validator exists so the module can enforce the check under its own configuration key and fail
 * startup when the list is empty, instead of leaving it to be forgotten.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ERROR = new OAuth2Error(
            "invalid_token", "The token's audience does not include this service", null);

    private final Set<String> acceptedAudiences;

    public AudienceValidator(Set<String> acceptedAudiences) {
        this.acceptedAudiences = Set.copyOf(acceptedAudiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audience = token.getClaimAsStringList(JwtClaimNames.AUD);
        if (audience != null && audience.stream().anyMatch(acceptedAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(ERROR);
    }
}
