package ru.ludwigandreas.restclient.spi;

/**
 * Turns an {@code auth.type} into a working {@link ClientAuthenticator}. The primary extension
 * point of this starter.
 *
 * <p>Providers are ordinary Spring beans, discovered by type. Adding an authentication method to the
 * platform is therefore publishing one bean in the service that needs it, with no change to this
 * module and no switch statement anywhere to extend:
 *
 * <pre>{@code
 * @Bean
 * ClientAuthenticationProvider hawkAuthProvider() {
 *     return new HawkAuthenticationProvider();
 * }
 * }</pre>
 *
 * <p>A provider whose {@link #type()} equals one of the built-in types replaces it - the built-ins
 * are registered with {@code @ConditionalOnMissingBean}-style precedence, so a service that needs a
 * different notion of "bearer" overrides it rather than working around it.
 */
public interface ClientAuthenticationProvider {

    /** The {@code auth.type} value this provider answers to, e.g. {@code oauth2-client-credentials}. */
    String type();

    /**
     * The properties class the {@code auth} block is bound to.
     *
     * <p>Must be a mutable JavaBean; see {@link ClientAuthProperties} for why a record will not do.
     */
    Class<? extends ClientAuthProperties> propertiesType();

    /**
     * Builds the authenticator for one named client.
     *
     * <p>Called once per client at context startup, so anything expensive - discovering an issuer,
     * opening a keystore - belongs here rather than on the request path. Throwing from here fails
     * the context, which is the correct outcome: a service that cannot authenticate to a dependency
     * is not ready to serve.
     *
     * @param clientName the client being configured, for error messages and metric tags
     * @param properties an instance of {@link #propertiesType()}, already merged from the defaults
     *                   block and the client block
     */
    ClientAuthenticator create(String clientName, ClientAuthProperties properties);
}
