package ru.ludwigandreas.restclient.config;

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.restclient.spi.ClientAuthProperties;

/**
 * The {@code auth} block for the authentication types this starter ships.
 *
 * <p>One flat class covering seven types rather than seven classes with a discriminator, because
 * that is what the YAML looks like - {@code type} and then the two or three keys that type needs -
 * and because Spring's configuration-metadata processor can describe a flat class, so an IDE
 * completes {@code registration-id} the moment {@code type: oauth2-client-credentials} is typed. The
 * price is that a field can be set for a type that ignores it; the startup validator reports exactly
 * that rather than letting an operator believe a password took effect on a bearer client.
 *
 * <p>Custom providers bind their own {@link ClientAuthProperties} class instead, so nothing here
 * constrains an extension.
 *
 * <p><strong>Nothing in this class is ever logged, tagged onto a metric, or written to an audit
 * record.</strong> {@code toString()} is not generated for that reason - Lombok's would print the
 * secret on the first {@code log.debug("{}", props)} somebody adds.
 */
@Getter
@Setter
public class AuthProperties implements ClientAuthProperties {

    /**
     * The authentication scheme: {@code none}, {@code basic}, {@code bearer}, {@code api-key},
     * {@code oauth2-client-credentials}, {@code oauth2-token-relay}, {@code custom}, or the
     * {@code type()} of any {@code ClientAuthenticationProvider} bean the service publishes.
     * Built-in default: {@code none}.
     */
    private String type;

    /** {@code basic}: username. */
    private String username;

    /** {@code basic}: password. Resolve it from Vault or an environment variable, never a literal. */
    private String password;

    /**
     * {@code bearer}: a static token, typically {@code ${PARTNER_TOKEN}}.
     *
     * <p>Mutually exclusive with {@link #getTokenSupplier()}; setting both is a startup error rather
     * than a silent precedence rule nobody can remember.
     */
    private String token;

    /** {@code bearer}: bean name of a {@code TokenSupplier} that produces the token instead. */
    private String tokenSupplier;

    /** {@code bearer}: the scheme written before the token. Built-in default: {@code Bearer}. */
    private String scheme;

    /** {@code api-key}: header to carry the key. Built-in default: {@code X-Api-Key}. */
    private String headerName;

    /**
     * {@code api-key}: query parameter to carry the key instead of a header.
     *
     * <p>Setting this puts a credential in the URI, which means it reaches access logs, proxies and
     * browser history. Supported because some partners offer nothing else; the startup validator
     * warns, loudly, once per client.
     */
    private String queryParamName;

    /** {@code api-key}: the key itself. */
    private String key;

    /** {@code api-key}: text prefixed to the value, e.g. {@code Token }. */
    private String valuePrefix;

    /**
     * {@code oauth2-client-credentials}: the Spring Security {@code ClientRegistration} id, i.e. a
     * key under {@code spring.security.oauth2.client.registration}.
     *
     * <p>Deliberately a reference rather than a copy of the client id and secret: the registration
     * is where the platform already keeps them, it is already wired to Vault, and duplicating a
     * secret into a second property is how one of the two copies gets rotated.
     */
    private String registrationId;

    /**
     * How long before expiry a cached token is renewed. Built-in default: 30s.
     *
     * <p>Without skew, a token that expires in 200ms is used for a call that takes 300ms, and the
     * failure looks like an intermittent 401 with no pattern.
     */
    private Duration refreshSkew;

    /** Scopes requested, overriding the registration's own. Rarely needed. */
    private List<String> scopes;

    /**
     * Retry once with a forcibly refreshed token after a 401. Built-in default: true for the cached
     * schemes, false for the static ones.
     *
     * <p>The retry is one extra attempt, outside the resilience retry and not counted against its
     * budget: it is not a transport retry, it is the correct response to a credential the issuer
     * revoked early.
     */
    private Boolean retryOnUnauthorized;

    /**
     * {@code oauth2-token-relay}: propagate the inbound user's access token to this dependency.
     *
     * <p>Must be set to {@code true} explicitly per client; it is never inherited from the
     * {@code defaults} block, and the merger enforces that. Relaying a user's token is handing this
     * service's callers' credentials to a third party, and "every client inherited it from defaults"
     * is not a sentence anyone wants to write in an incident report.
     */
    private Boolean relayEnabled;

    /**
     * {@code oauth2-token-relay}: what to do when there is no inbound token. Built-in default:
     * {@code true}, meaning fail.
     *
     * <p>Failing closed is the only safe default. The alternative - calling anonymously - turns "the
     * user was not authenticated" into "the call succeeded with whatever the partner grants
     * anonymous callers", which is a privilege escalation that produces no error anywhere.
     */
    private Boolean failWhenNoToken;

    /** {@code custom}: bean name of a {@code ClientAuthenticator} to delegate to. */
    private String authenticator;
}
