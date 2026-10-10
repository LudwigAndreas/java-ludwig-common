package ru.ludwigandreas.notification.local;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.StubAddress;

/**
 * The identity provider, as far as this service uses one: discovery metadata, signing keys, and
 * tokens signed with them.
 *
 * <h2>Why a stand-in issuer and not a bypass</h2>
 *
 * <p>A local run is called from outside the JVM - curl, a browser, another service - so a principal
 * cannot be injected the way a MockMvc test injects one. The two ways left are a header-based
 * backdoor, which is code that then has to be kept out of an image for ever, and this: an issuer the
 * service's <em>production</em> decoder can validate against. With it the audience check, the tenant
 * claim and the authority lookup all run as deployed, and "a token for the wrong audience is a 401"
 * is something you can put a breakpoint on.
 *
 * <p>The service finds it through {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, which
 * is the address this stub publishes.
 *
 * <h2>What a token here can and cannot do</h2>
 *
 * <p>It says who the caller is. It grants nothing: this service resolves authorities from its
 * identity projection, so a minted token for a subject with no projected user authenticates and is
 * then allowed nothing. See {@link LocalCaller}.
 *
 * <p>The key pair is generated when the stub is created and lives only in this JVM. There is no
 * secret here to leak and nothing a deployed service would trust.
 */
public class IdentityProviderStub implements PartnerStub {

    /** This stub's name. */
    public static final String NAME = "identity";

    /** The audience this service requires, as {@code ludwig.security.jwt.audiences} sets it. */
    public static final String AUDIENCE = "notification-service";

    private static final String JWKS_PATH = "/protocol/openid-connect/certs";
    private static final int KEY_BITS = 2048;

    private final RSAKey signingKey;
    private volatile String issuer;

    /** Creates the stand-in with a fresh signing key. */
    public IdentityProviderStub() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_BITS);
            KeyPair pair = generator.generateKeyPair();
            this.signingKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("local-" + UUID.randomUUID())
                    .build();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("This JVM has no RSA key generator", ex);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public StubAddress address() {
        return StubAddress.property("spring.security.oauth2.resourceserver.jwt.issuer-uri");
    }

    @Override
    public void register(WireMockServer server) {
        // The issuer in the metadata has to be the address the service was given, to the character,
        // or the decoder refuses the provider. It is only known once the server has a port.
        issuer = "http://127.0.0.1:" + server.port();
        server.stubFor(get(urlPathEqualTo("/.well-known/openid-configuration")).willReturn(okJson("""
                {
                  "issuer": "%s",
                  "jwks_uri": "%s%s",
                  "authorization_endpoint": "%s/protocol/openid-connect/auth",
                  "token_endpoint": "%s/protocol/openid-connect/token",
                  "response_types_supported": ["code"],
                  "subject_types_supported": ["public"],
                  "id_token_signing_alg_values_supported": ["RS256"]
                }
                """.formatted(issuer, issuer, JWKS_PATH, issuer, issuer))));
        server.stubFor(get(urlPathEqualTo(JWKS_PATH))
                .willReturn(okJson(new JWKSet(signingKey.toPublicJWK()).toString())));
    }

    /**
     * A token for a seeded caller, valid for this service.
     *
     * @param caller   who the token says the bearer is
     * @param lifetime how long it stays valid
     * @return the serialized token
     */
    public String tokenFor(LocalCaller caller, Duration lifetime) {
        return mint(caller.subject(), caller.displayName(), LocalCaller.TENANT, AUDIENCE, lifetime);
    }

    /**
     * A token with every claim chosen by the caller, for the cases that are meant to be refused.
     *
     * @param subject  the {@code sub} claim
     * @param name     the {@code name} claim
     * @param tenant   the {@code tenant_id} claim
     * @param audience the {@code aud} claim
     * @param lifetime how long it stays valid
     * @return the serialized token
     */
    public String mint(String subject, String name, String tenant, String audience, Duration lifetime) {
        if (issuer == null) {
            throw new IllegalStateException("The identity stand-in has not been started yet");
        }
        return mintAs(issuer, subject, name, tenant, audience, lifetime);
    }

    /**
     * A token that claims a given issuer, signed with this instance's key.
     *
     * <p>For forging: a second instance of this class has a key the running issuer does not publish,
     * so a token it signs is one the service must refuse whatever it claims.
     *
     * @param claimedIssuer the {@code iss} claim
     * @param subject       the {@code sub} claim
     * @param name          the {@code name} claim
     * @param tenant        the {@code tenant_id} claim
     * @param audience      the {@code aud} claim
     * @param lifetime      how long it stays valid
     * @return the serialized token
     */
    public String mintAs(String claimedIssuer, String subject, String name, String tenant, String audience,
                         Duration lifetime) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(claimedIssuer)
                .subject(subject)
                .audience(audience)
                .claim("name", name)
                .claim("tenant_id", tenant)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        try {
            token.sign(new RSASSASigner(signingKey));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not sign a local token", ex);
        }
        return token.serialize();
    }
}
