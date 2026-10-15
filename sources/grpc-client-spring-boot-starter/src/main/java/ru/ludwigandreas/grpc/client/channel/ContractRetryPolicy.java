package ru.ludwigandreas.grpc.client.channel;

import com.google.protobuf.DescriptorProtos.MethodOptions.IdempotencyLevel;
import com.google.protobuf.Descriptors;
import io.grpc.ServiceDescriptor;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;
import ru.ludwigandreas.grpc.client.properties.ClientSettings;

/**
 * Which methods may be retried, read from the contracts - never from a call site.
 *
 * <p>Every contract on the classpath is a generated {@code <Service>Grpc} class under
 * {@code ru.ludwigandreas.<service>.grpc.v<N>}, a placement {@code check_protos.sh} enforces, so the
 * scan is bounded to that convention. A method is retried only when its {@code .proto} declares
 * {@code idempotency_level = IDEMPOTENT} or {@code NO_SIDE_EFFECTS}: whether a repeat is safe is
 * the server owner's statement, written once in the contract, and a caller cannot overrule it.
 * Everything else gets gRPC's transparent retry alone, which repeats only a request that provably
 * never left the process.
 *
 * <p>A full method name is unique across services, so one list serves every client; what differs
 * per client is the policy - attempts, backoff, codes, throttling.
 */
public final class ContractRetryPolicy {

    private static final String CONTRACT_ROOT = "ru.ludwigandreas";
    private static final double MILLIS_PER_SECOND = 1000.0;
    private static final Set<IdempotencyLevel> SAFE =
            Set.of(IdempotencyLevel.IDEMPOTENT, IdempotencyLevel.NO_SIDE_EFFECTS);

    private static volatile List<Map<String, Object>> retryable;

    private ContractRetryPolicy() {
    }

    /**
     * The service config a client's channel starts with.
     *
     * @param retry the client's resolved retry settings
     * @return the service config, as gRPC's JSON-shaped map
     */
    public static Map<String, Object> serviceConfig(ClientSettings.Retry retry) {
        Map<String, Object> config = new LinkedHashMap<>();
        List<Map<String, Object>> names = retryableMethods();
        if (Boolean.TRUE.equals(retry.getEnabled()) && !names.isEmpty()) {
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("maxAttempts", (double) retry.getMaxAttempts());
            policy.put("initialBackoff", seconds(retry.getInitialBackoff().toMillis()));
            policy.put("maxBackoff", seconds(retry.getMaxBackoff().toMillis()));
            policy.put("backoffMultiplier", retry.getBackoffMultiplier());
            policy.put("retryableStatusCodes", List.copyOf(retry.getRetryOn()));
            config.put("methodConfig", List.of(Map.of("name", names, "retryPolicy", policy)));
            config.put("retryThrottling", Map.of(
                    "maxTokens", (double) retry.getThrottlingMaxTokens(),
                    "tokenRatio", retry.getThrottlingTokenRatio()));
        }
        return config;
    }

    /**
     * Every method the contracts on the classpath declare safe to repeat, as service-config names.
     *
     * @return the names, computed once
     */
    static List<Map<String, Object>> retryableMethods() {
        List<Map<String, Object>> found = retryable;
        if (found == null) {
            found = scan();
            retryable = found;
        }
        return found;
    }

    private static List<Map<String, Object>> scan() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter((reader, factory) -> reader.getClassMetadata().getClassName().endsWith("Grpc"));
        List<Map<String, Object>> names = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(CONTRACT_ROOT)) {
            ServiceDescriptor service = serviceDescriptor(candidate.getBeanClassName());
            if (service != null && service.getSchemaDescriptor() instanceof ProtoServiceDescriptorSupplier proto) {
                for (Descriptors.MethodDescriptor method : proto.getServiceDescriptor().getMethods()) {
                    if (SAFE.contains(method.getOptions().getIdempotencyLevel())) {
                        names.add(Map.of("service", service.getName(), "method", method.getName()));
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    private static ServiceDescriptor serviceDescriptor(String className) {
        try {
            Class<?> type = ClassUtils.forName(className, ContractRetryPolicy.class.getClassLoader());
            Method accessor = type.getMethod("getServiceDescriptor");
            return accessor.invoke(null) instanceof ServiceDescriptor service ? service : null;
        } catch (ReflectiveOperationException | LinkageError notAContract) {
            return null;
        }
    }

    private static String seconds(long millis) {
        return millis / MILLIS_PER_SECOND + "s";
    }
}
