package ru.ludwigandreas.restclient.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.restclient.config.AuthProperties;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.RestClientProperties;
import ru.ludwigandreas.restclient.spi.ClientAuthProperties;
import ru.ludwigandreas.restclient.spi.ClientAuthenticationProvider;
import ru.ludwigandreas.restclient.spi.ClientAuthenticator;

/**
 * Picks the {@link ClientAuthenticationProvider} for a client's {@code auth.type} and hands it its
 * configuration.
 *
 * <h2>Two binding paths, and why there are two</h2>
 *
 * <p>A provider whose {@code propertiesType()} is {@link AuthProperties} - every built-in one - is
 * handed the already-merged instance from {@code ClientPropertiesMerger}. That path carries the
 * starter's built-in defaults (the {@code Bearer} scheme, the 30-second refresh skew) and the one
 * rule the merger enforces about {@code relay-enabled} never being inherited.
 *
 * <p>A provider with its own properties class is bound by Spring's {@code Binder}, twice onto the
 * same instance: first from {@code ludwig.rest-client.defaults.auth}, then from the client's own
 * {@code auth} block. Binding onto an existing instance is what gives a custom auth method exactly
 * the same inheritance a built-in one has, without this class knowing a single field name of it.
 * The one thing it cannot do is apply built-in defaults for keys it has never heard of - which is
 * why a custom properties class initializes its own fields.
 */
public class ClientAuthenticatorFactory {

    private final Map<String, ClientAuthenticationProvider> providersByType = new LinkedHashMap<>();
    private final Environment environment;

    /**
     * Indexes every provider in the context by the {@code auth.type} it answers to.
     *
     * @param providers every provider bean in the context. Later beans win on a duplicate type, which
     *                  is what lets a service override a built-in scheme by publishing a provider
     *                  with the same {@code type()} - the built-ins are registered first
     */
    public ClientAuthenticatorFactory(List<ClientAuthenticationProvider> providers, Environment environment) {
        this.environment = environment;
        for (ClientAuthenticationProvider provider : providers) {
            providersByType.put(normalize(provider.type()), provider);
        }
    }

    /** The authentication types this context can serve, for error messages. */
    public java.util.Set<String> knownTypes() {
        return providersByType.keySet();
    }

    /** Builds the authenticator for one named client. */
    public ClientAuthenticator create(String clientName, ClientProperties merged) {
        String type = normalize(merged.getAuth().getType());
        ClientAuthenticationProvider provider = providersByType.get(type);
        if (provider == null) {
            throw new IllegalStateException("Client '" + clientName + "': unknown auth.type '" + type
                    + "'. Known types: " + String.join(", ", providersByType.keySet())
                    + ". A new type is added by publishing a ClientAuthenticationProvider bean.");
        }
        return provider.create(clientName, propertiesFor(clientName, provider, merged));
    }

    private ClientAuthProperties propertiesFor(String clientName, ClientAuthenticationProvider provider,
                                               ClientProperties merged) {
        Class<? extends ClientAuthProperties> type = provider.propertiesType();
        if (type.isInstance(merged.getAuth())) {
            return merged.getAuth();
        }
        ClientAuthProperties target = BeanUtils.instantiateClass(type);
        Binder binder = Binder.get(environment);
        binder.bind(RestClientProperties.PREFIX + ".defaults.auth", Bindable.ofInstance(target));
        binder.bind(RestClientProperties.PREFIX + ".clients." + clientName + ".auth",
                Bindable.ofInstance(target));
        return target;
    }

    private String normalize(String type) {
        // Types are compared lower-cased so that `type: BEARER` and `type: bearer` are the same
        // thing - relaxed binding does this for property names and does not do it for values.
        return type == null ? "none" : type.trim().toLowerCase(Locale.ROOT);
    }
}
