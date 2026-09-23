package ru.ludwigandreas.restclient.auth.provider;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import ru.ludwigandreas.restclient.config.AuthProperties;
import ru.ludwigandreas.restclient.config.AuthTypes;
import ru.ludwigandreas.restclient.spi.ClientAuthProperties;
import ru.ludwigandreas.restclient.spi.ClientAuthenticationProvider;
import ru.ludwigandreas.restclient.spi.ClientAuthenticator;

/**
 * Delegates to a {@link ClientAuthenticator} bean the service publishes.
 *
 * <p>The lighter of the two extension routes. A full {@link ClientAuthenticationProvider} is the one
 * to write when the scheme needs its own configuration keys and may be used by several clients;
 * {@code type: custom} with {@code auth.authenticator: mySigner} is enough when there is one client
 * and the authenticator gets everything it needs from its own constructor.
 */
public class CustomAuthenticationProvider implements ClientAuthenticationProvider {

    private final BeanFactory beanFactory;

    public CustomAuthenticationProvider(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public String type() {
        return AuthTypes.CUSTOM;
    }

    @Override
    public Class<? extends ClientAuthProperties> propertiesType() {
        return AuthProperties.class;
    }

    @Override
    public ClientAuthenticator create(String clientName, ClientAuthProperties properties) {
        AuthProperties auth = (AuthProperties) properties;
        String beanName = auth.getAuthenticator();
        if (beanName == null || beanName.isBlank()) {
            throw new IllegalStateException("Client '" + clientName
                    + "': auth.type=custom requires auth.authenticator to name a "
                    + "ClientAuthenticator bean.");
        }
        try {
            return beanFactory.getBean(beanName, ClientAuthenticator.class);
        } catch (NoSuchBeanDefinitionException ex) {
            throw new IllegalStateException("Client '" + clientName + "': auth.authenticator names "
                    + "bean '" + beanName + "', which is not a ClientAuthenticator bean in this "
                    + "context.", ex);
        }
    }
}
