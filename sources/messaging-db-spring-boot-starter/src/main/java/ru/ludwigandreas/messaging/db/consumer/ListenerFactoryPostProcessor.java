package ru.ludwigandreas.messaging.db.consumer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;

/**
 * Attaches the log transport to listener container factories without touching the code that builds them.
 *
 * <p>Two steps, both by post-processing:
 *
 * <ol>
 *   <li>messaging's {@link ListenerContainerFactoryBuilder} is proxied so that every factory it returns is
 *       remembered with the consumer name it was asked for. The factory itself does not carry the name, and
 *       the name is what the consumer's retry and dead-letter settings are resolved by.</li>
 *   <li>every listener container factory bean is replaced by a {@link RoutingListenerContainerFactory}
 *       standing in for it.</li>
 * </ol>
 *
 * <p>The builder is necessarily post-processed first: the factory {@code @Bean} methods take it as an
 * argument, so it is fully initialized - proxy included - before any of them runs.
 */
public class ListenerFactoryPostProcessor implements BeanPostProcessor {

    private static final Set<String> BUILD_METHODS = Set.of("forJsonPayload", "forTextPayload");

    private final Map<Object, String> consumerNames = Collections.synchronizedMap(new IdentityHashMap<>());
    private final ObjectProvider<ConsumerSupport> support;

    /**
     * Creates the post-processor.
     *
     * @param support the shared collaborators, resolved lazily: a post-processor must not pull ordinary
     *                beans into existence while the context is still registering post-processors
     */
    public ListenerFactoryPostProcessor(ObjectProvider<ConsumerSupport> support) {
        this.support = support;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ListenerContainerFactoryBuilder) {
            ProxyFactory proxy = new ProxyFactory(bean);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                Object factory = invocation.proceed();
                if (factory != null && BUILD_METHODS.contains(invocation.getMethod().getName())) {
                    consumerNames.put(factory, (String) invocation.getArguments()[0]);
                }
                return factory;
            });
            return proxy.getProxy(bean.getClass().getClassLoader());
        }
        if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
            return new RoutingListenerContainerFactory(factory, consumerNames.get(factory), beanName, support);
        }
        return bean;
    }
}
