package ru.ludwigandreas.restclient.spi;

/**
 * Marker for the configuration a {@link ClientAuthenticationProvider} binds its {@code auth} block
 * to.
 *
 * <p>It carries no methods on purpose. A provider declares its own properties class, the starter
 * binds {@code ludwig.rest-client.defaults.auth} and then
 * {@code ludwig.rest-client.clients.<name>.auth} onto one instance of it - which is how a custom
 * auth method inherits from the defaults block exactly like a built-in one - and hands the result
 * back to the provider, which knows its own type.
 *
 * <p>The properties class must be an ordinary mutable JavaBean, because that is what Spring's
 * {@code Binder} can bind twice onto the same instance; a record cannot express "inherit this field
 * from the defaults block".
 */
public interface ClientAuthProperties {
}
