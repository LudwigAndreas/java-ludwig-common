package ru.ludwigandreas.grpc.client.config;

import io.grpc.Channel;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import ru.ludwigandreas.grpc.client.GrpcChannelRegistry;
import ru.ludwigandreas.grpc.client.LudwigGrpcClient;
import ru.ludwigandreas.grpc.client.channel.GrpcStubs;
import ru.ludwigandreas.grpc.client.error.GrpcClientConfigurationException;
import ru.ludwigandreas.grpc.client.error.GrpcClientMessages;
import ru.ludwigandreas.grpc.client.properties.ClientSettings;
import ru.ludwigandreas.grpc.client.properties.GrpcClientProperties;

/**
 * Registers a {@code Channel} bean per configured client - named {@code <name>GrpcChannel},
 * qualified {@code <name>} - and a stub bean for every {@code @LudwigGrpcClient} injection point it
 * finds, so a field, a constructor parameter and a {@code @Bean} method parameter all work.
 *
 * <p>Injection points are found before any bean exists, which is what lets an unknown client name
 * fail startup naming the injection point instead of surfacing as a missing bean.
 */
public class GrpcClientBeanRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        Set<String> clients = new TreeSet<>(Binder.get(environment)
                .bind(GrpcClientProperties.PREFIX + ".clients", Bindable.mapOf(String.class, ClientSettings.class))
                .orElse(Map.of()).keySet());
        BeanFactory beans = (BeanFactory) registry;
        for (String client : clients) {
            RootBeanDefinition channel = new RootBeanDefinition(Channel.class,
                    () -> beans.getBean(GrpcChannelRegistry.class).channel(client));
            channel.addQualifier(new AutowireCandidateQualifier(Qualifier.class, client));
            channel.addQualifier(new AutowireCandidateQualifier(LudwigGrpcClient.class, client));
            registry.registerBeanDefinition(client + "GrpcChannel", channel);
        }
        List<String> problems = new ArrayList<>();
        Set<String> registered = new LinkedHashSet<>();
        for (String beanName : registry.getBeanDefinitionNames()) {
            for (InjectionPoint point : inspect(registry, registry.getBeanDefinition(beanName))) {
                if (!clients.contains(point.client())) {
                    problems.add(GrpcClientMessages.startup(
                            "unknown-client", point.description(), point.client(), clients));
                } else if (point.type() != Channel.class && !GrpcStubs.isStub(point.type())) {
                    problems.add(GrpcClientMessages.startup("not-a-stub", point.description(), point.type().getName()));
                } else if (point.type() != Channel.class
                        && registered.add(point.client() + "/" + point.type().getName())) {
                    registerStub(registry, beans, point.client(), point.type());
                }
            }
        }
        if (!problems.isEmpty()) {
            StringBuilder listed = new StringBuilder();
            problems.forEach(problem -> listed.append(System.lineSeparator()).append("  - ").append(problem));
            throw new GrpcClientConfigurationException(
                    GrpcClientMessages.startup("invalid", Integer.toString(problems.size()), listed));
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Everything happens while definitions can still be added.
    }

    private static <S> void registerStub(BeanDefinitionRegistry registry, BeanFactory beans, String client,
                                         Class<S> type) {
        RootBeanDefinition stub = new RootBeanDefinition(type,
                () -> GrpcStubs.create(type, beans.getBean(GrpcChannelRegistry.class).channel(client)));
        stub.addQualifier(new AutowireCandidateQualifier(LudwigGrpcClient.class, client));
        registry.registerBeanDefinition("ludwigGrpcStub." + client + "." + type.getName(), stub);
    }

    /**
     * A bean class whose members cannot be introspected - an auto-configuration naming an absent
     * optional class - has no injection point of ours, and is skipped.
     */
    private static List<InjectionPoint> inspect(BeanDefinitionRegistry registry, BeanDefinition definition) {
        try {
            return injectionPoints(registry, definition);
        } catch (LinkageError | IllegalStateException unintrospectable) {
            return List.of();
        }
    }

    private static List<InjectionPoint> injectionPoints(BeanDefinitionRegistry registry, BeanDefinition definition) {
        List<InjectionPoint> points = new ArrayList<>();
        Class<?> type = beanClass(registry, definition);
        if (type == null) {
            return points;
        }
        if (definition.getFactoryMethodName() != null) {
            for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
                if (method.getName().equals(definition.getFactoryMethodName())) {
                    parameters(method, points);
                    // The bean a @Bean method returns is autowired too: its fields and setters count.
                    members(method.getReturnType(), points);
                }
            }
            return points;
        }
        for (Executable constructor : type.getDeclaredConstructors()) {
            parameters(constructor, points);
        }
        members(type, points);
        return points;
    }

    private static void members(Class<?> type, List<InjectionPoint> points) {
        ReflectionUtils.doWithFields(type, field -> add(points, field.getAnnotation(LudwigGrpcClient.class),
                field.getType(), field.getDeclaringClass().getName() + "." + field.getName()),
                field -> field.isAnnotationPresent(LudwigGrpcClient.class));
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
            parameters(method, points);
        }
    }

    private static void parameters(Executable executable, List<InjectionPoint> points) {
        for (Parameter parameter : executable.getParameters()) {
            add(points, parameter.getAnnotation(LudwigGrpcClient.class), parameter.getType(),
                    executable.getDeclaringClass().getName() + "." + executable.getName()
                            + "(" + parameter.getName() + ")");
        }
    }

    private static void add(List<InjectionPoint> points, LudwigGrpcClient annotation, Class<?> type, String where) {
        if (annotation != null) {
            points.add(new InjectionPoint(annotation.value(), type, where));
        }
    }

    /**
     * The class whose members are inspected: the bean's own class, or - for a {@code @Bean}
     * method - the configuration class declaring it.
     */
    private static Class<?> beanClass(BeanDefinitionRegistry registry, BeanDefinition definition) {
        String className = definition.getBeanClassName();
        String factoryBean = definition.getFactoryBeanName();
        if (factoryBean != null && registry.containsBeanDefinition(factoryBean)) {
            className = registry.getBeanDefinition(definition.getFactoryBeanName()).getBeanClassName();
        }
        if (className == null) {
            return null;
        }
        try {
            return ClassUtils.forName(className, GrpcClientBeanRegistrar.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError unresolvable) {
            return null;
        }
    }

    private record InjectionPoint(String client, Class<?> type, String description) {
    }
}
