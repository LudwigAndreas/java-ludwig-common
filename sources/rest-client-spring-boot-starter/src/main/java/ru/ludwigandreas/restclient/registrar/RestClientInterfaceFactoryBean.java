package ru.ludwigandreas.restclient.registrar;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.Setter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import ru.ludwigandreas.restclient.config.ClientMode;
import ru.ludwigandreas.restclient.core.ClientRuntime;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.spi.FallbackHandler;

/**
 * Turns one {@code @LudwigRestClient} interface into a bean.
 *
 * <p>All the wiring a consuming service would otherwise write - an adapter, an
 * {@code HttpServiceProxyFactory}, a {@code @Bean} method per interface - happens here, once, from
 * the client's configured mode.
 *
 * <h2>Startup validation of return types</h2>
 *
 * <p>A {@code sync} client cannot return a {@code Mono}, and an {@code async} one cannot return a
 * value type. Left unchecked, the first produces a proxy that fails on the first call with a
 * {@code ClassCastException} from inside Spring, and the second blocks a Netty event loop. Both are
 * checked here, and the message names the interface, the method and the client, because that is what
 * the person reading it has to change.
 */
@Setter
public class RestClientInterfaceFactoryBean
        implements FactoryBean<Object>, BeanFactoryAware, InitializingBean {

    private static final List<String> REACTIVE_TYPES =
            List.of("reactor.core.publisher.Mono", "reactor.core.publisher.Flux");

    private Class<?> interfaceType;
    private String clientName;

    private BeanFactory beanFactory;
    private Object proxy;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterPropertiesSet() {
        RestClientRegistry registry = beanFactory.getBean(RestClientRegistry.class);
        if (!registry.contains(clientName)) {
            throw new IllegalStateException(interfaceType.getName() + " is bound to REST client '"
                    + clientName + "', which is not configured. Configured clients: "
                    + String.join(", ", registry.names()) + ".");
        }
        ClientRuntime runtime = registry.runtime(clientName);
        ClientMode mode = runtime.getProperties().getMode();
        validateReturnTypes(mode);

        HttpServiceProxyFactory factory = mode == ClientMode.ASYNC
                ? HttpServiceProxyFactory.builderFor(
                        WebClientAdapter.create(registry.reactive(clientName))).build()
                : HttpServiceProxyFactory.builderFor(
                        RestClientAdapter.create(registry.rest(clientName))).build();
        Object client = factory.createClient(interfaceType);
        this.proxy = wrapWithFallback(client, runtime);
    }

    @Override
    public Object getObject() {
        return proxy;
    }

    @Override
    public Class<?> getObjectType() {
        return interfaceType;
    }

    private void validateReturnTypes(ClientMode mode) {
        List<String> problems = new ArrayList<>();
        for (Method method : interfaceType.getMethods()) {
            if (method.isDefault() || method.isSynthetic()) {
                continue;
            }
            boolean reactive = isReactive(method.getReturnType());
            if (mode == ClientMode.SYNC && reactive) {
                problems.add(method.getName() + " returns " + method.getReturnType().getSimpleName()
                        + ", but client '" + clientName + "' is mode=sync. Set mode: async on the "
                        + "client, or return a value type.");
            }
            if (mode == ClientMode.ASYNC && !reactive && method.getReturnType() != void.class) {
                problems.add(method.getName() + " returns " + method.getReturnType().getSimpleName()
                        + ", but client '" + clientName + "' is mode=async. A blocking return type "
                        + "there would block an event loop. Return Mono, Flux or CompletableFuture, "
                        + "or set mode: sync on the client.");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(interfaceType.getName() + " does not match its client's "
                    + "mode:\n  - " + String.join("\n  - ", problems));
        }
    }

    private boolean isReactive(Class<?> returnType) {
        return CompletableFuture.class.isAssignableFrom(returnType)
                || CompletionStage.class.isAssignableFrom(returnType)
                || REACTIVE_TYPES.contains(returnType.getName());
    }

    /**
     * Wraps the proxy so that a configured fallback can answer for a call that could not be made.
     *
     * <p>Only when the client actually declares one, so an interface on a client with no fallback is
     * the bare Spring proxy and pays nothing. The wrapper is a JDK dynamic proxy because the target
     * is already one; there is no class to subclass.
     */
    private Object wrapWithFallback(Object client, ClientRuntime runtime) {
        Map<String, String> perMethod = runtime.getProperties().getResilience().getFallback().getMethods();
        String global = runtime.getProperties().getResilience().getFallback().getHandler();
        if (global == null && perMethod.isEmpty()) {
            return client;
        }
        FallbackInvocationHandler handler = new FallbackInvocationHandler(client, clientName,
                interfaceType, global, perMethod, this::fallbackBean);
        return Proxy.newProxyInstance(interfaceType.getClassLoader(),
                new Class<?>[]{interfaceType}, handler);
    }

    private FallbackHandler fallbackBean(String beanName) {
        try {
            return beanFactory.getBean(beanName, FallbackHandler.class);
        } catch (org.springframework.beans.BeansException ex) {
            throw new IllegalStateException("Client '" + clientName + "': resilience.fallback names "
                    + "bean '" + beanName + "', which is not a FallbackHandler bean in this context.",
                    ex);
        }
    }
}
