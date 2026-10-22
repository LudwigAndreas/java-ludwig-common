package ru.ludwigandreas.asyncapi.document;

import static ru.ludwigandreas.messaging.api.MessageHeaders.AGGREGATE_ID;
import static ru.ludwigandreas.messaging.api.MessageHeaders.AGGREGATE_TYPE;
import static ru.ludwigandreas.messaging.api.MessageHeaders.EVENT_TYPE;
import static ru.ludwigandreas.messaging.api.MessageHeaders.EVENT_VERSION;
import static ru.ludwigandreas.messaging.api.MessageHeaders.IDEMPOTENCY_KEY;
import static ru.ludwigandreas.messaging.api.MessageHeaders.PRODUCED_AT;
import static ru.ludwigandreas.messaging.api.MessageHeaders.TRACE_ID;

import io.github.springwolf.asyncapi.v3.model.AsyncAPI;
import io.github.springwolf.asyncapi.v3.model.channel.ChannelObject;
import io.github.springwolf.asyncapi.v3.model.channel.ChannelReference;
import io.github.springwolf.asyncapi.v3.model.channel.message.Message;
import io.github.springwolf.asyncapi.v3.model.channel.message.MessageHeaders;
import io.github.springwolf.asyncapi.v3.model.channel.message.MessageObject;
import io.github.springwolf.asyncapi.v3.model.channel.message.MessagePayload;
import io.github.springwolf.asyncapi.v3.model.channel.message.MessageReference;
import io.github.springwolf.asyncapi.v3.model.components.ComponentSchema;
import io.github.springwolf.asyncapi.v3.model.operation.Operation;
import io.github.springwolf.asyncapi.v3.model.operation.OperationAction;
import io.github.springwolf.asyncapi.v3.model.schema.MultiFormatSchema;
import io.github.springwolf.asyncapi.v3.model.schema.SchemaObject;
import io.github.springwolf.asyncapi.v3.model.server.ServerReference;
import io.github.springwolf.core.asyncapi.AsyncApiCustomizer;
import io.github.springwolf.core.asyncapi.components.ComponentsService;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import ru.ludwigandreas.messaging.api.DeadLetterTopics;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;
import ru.ludwigandreas.messaging.settings.KafkaClusters;
import ru.ludwigandreas.messaging.settings.MessagingProperties;
import ru.ludwigandreas.messaging.settings.ResolvedConsumerSettings;
import ru.ludwigandreas.outbox.api.OutboxEventTypes;
import ru.ludwigandreas.outbox.config.OutboxProperties;

/**
 * Completes Springwolf's document with what only the platform knows.
 *
 * <p>Springwolf scans {@code @KafkaListener} methods and describes their payloads. It cannot know the
 * platform's envelope, which cluster a consumer reads from or where its dead letters go, or what the outbox
 * produces - an outbox event's payload is {@code Object} until {@link OutboxEventTypes} says otherwise. One
 * customizer, run after the scan, in a fixed order: producers first, so their channels exist when consumers
 * are bound to clusters, and the envelope last, so it reaches every message either step added.
 */
public class PlatformAsyncApiCustomizer implements AsyncApiCustomizer {

    /** The component schema every message's headers point at. */
    public static final String ENVELOPE_SCHEMA = "LudwigEnvelope";

    private static final String CONTENT_TYPE = "application/json";
    private static final String KAFKA = "KAFKA";
    private static final String KAFKA_CLUSTER = KAFKA + ":";
    /** Springwolf's own header schema for Spring's {@code __TypeId__}, which the platform switches off. */
    private static final String SPRING_TYPE_HEADERS = "SpringKafkaDefaultHeaders-";

    private final ConfigurableListableBeanFactory beans;
    private final ListenerContainerFactoryBuilder builder;
    private final MessagingProperties messaging;
    private final OutboxProperties outbox;
    private final OutboxEventTypes eventTypes;
    private final ComponentsService components;

    /**
     * Creates the customizer.
     *
     * @param beans      the context's bean factory, to find {@code @KafkaListener} methods and their factories
     * @param builder    messaging's builder, which knows each factory's consumer name
     * @param messaging  messaging's configuration, for each consumer's resolved settings and the correlation header
     * @param outbox     the outbox configuration, for the routes
     * @param eventTypes every declared outbox event type, merged
     * @param components Springwolf's schema service, which generates a declared payload's schema
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - collaborators assembled once by the autoconfiguration.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public PlatformAsyncApiCustomizer(ConfigurableListableBeanFactory beans, ListenerContainerFactoryBuilder builder,
                                      MessagingProperties messaging, OutboxProperties outbox,
                                      OutboxEventTypes eventTypes, ComponentsService components) {
        this.beans = beans;
        this.builder = builder;
        this.messaging = messaging;
        this.outbox = outbox;
        this.eventTypes = eventTypes;
        this.components = components;
    }

    @Override
    public void customize(AsyncAPI asyncApi) {
        producers(asyncApi);
        consumers(asyncApi);
        envelope(asyncApi);
    }

    /** One send operation per declared event type routed over a Kafka transport. */
    private void producers(AsyncAPI asyncApi) {
        eventTypes.declarations().forEach((eventType, declared) -> routeOf(eventType, declared).ifPresent(route -> {
            Class<?> payload = declared.payload();
            String cluster = clusterOf(route.getTransport());
            if (cluster == null) {
                return;
            }
            String topic = route.getDestination();
            ComponentSchema schema = components.resolvePayloadSchema(payload, CONTENT_TYPE);
            Object payloadSchema = schema.getReference() != null ? schema.getReference() : schema.getSchema();
            asyncApi.getComponents().getMessages().put(eventType, MessageObject.builder()
                    .messageId(eventType).name(eventType).title(eventType).contentType(CONTENT_TYPE)
                    .payload(MessagePayload.of(MultiFormatSchema.builder().schema(payloadSchema).build()))
                    .build());
            ChannelObject channel = asyncApi.getChannels().computeIfAbsent(topic, id -> ChannelObject.builder()
                    .channelId(id).address(id).messages(new HashMap<>()).build());
            Map<String, Message> messages = new LinkedHashMap<>(channel.getMessages() == null ? Map.of()
                    : channel.getMessages());
            messages.put(eventType, MessageReference.toComponentMessage(eventType));
            channel.setMessages(messages);
            channel.setServers(List.of(ServerReference.fromServer(cluster)));
            asyncApi.getOperations().put(topic + "_send_" + eventType, Operation.builder()
                    .action(OperationAction.SEND)
                    .title(eventType)
                    .description("Published through the outbox over transport '" + route.getTransport() + "'.")
                    .channel(ChannelReference.fromChannel(topic))
                    .messages(List.of(MessageReference.toChannelMessage(topic, eventType)))
                    .build());
        }));
        // Springwolf registers the schemas it generates in its own service; the document was assembled before
        // this ran, so the new ones are copied across.
        components.getSchemas().forEach(asyncApi.getComponents().getSchemas()::putIfAbsent);
    }

    /** Each consumer's channels bound to its cluster, plus its dead-letter channel. */
    private void consumers(AsyncAPI asyncApi) {
        for (String beanName : beans.getBeanDefinitionNames()) {
            Class<?> type = beans.getType(beanName, false);
            if (type == null) {
                continue;
            }
            Map<Method, KafkaListener> listeners = MethodIntrospector.selectMethods(AopUtils.isAopProxy(type)
                            ? type.getSuperclass() : type,
                    (MethodIntrospector.MetadataLookup<KafkaListener>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, KafkaListener.class));
            listeners.values().forEach(listener -> bind(asyncApi, listener));
        }
    }

    private void bind(AsyncAPI asyncApi, KafkaListener listener) {
        Optional<ResolvedConsumerSettings> settings = consumerNameOf(listener).map(messaging::resolve);
        if (settings.isEmpty()) {
            return;
        }
        ResolvedConsumerSettings resolved = settings.get();
        for (String topic : listener.topics()) {
            String address = beans.resolveEmbeddedValue(topic);
            ChannelObject channel = asyncApi.getChannels().get(address);
            if (channel == null) {
                continue;
            }
            channel.setServers(List.of(ServerReference.fromServer(resolved.cluster())));
            if (resolved.deadLetterEnabled()) {
                String deadLetters = DeadLetterTopics.forTopic(address, resolved.deadLetterSuffix());
                asyncApi.getChannels().putIfAbsent(deadLetters, ChannelObject.builder()
                        .channelId(deadLetters).address(deadLetters)
                        .description("Dead letters of the consumer of " + address + ".")
                        .messages(channel.getMessages() == null ? Map.of() : new LinkedHashMap<>(channel.getMessages()))
                        .servers(List.of(ServerReference.fromServer(resolved.deadLetterCluster())))
                        .build());
            }
        }
    }

    /** The platform envelope as every message's headers, replacing Spring's type-id header. */
    private void envelope(AsyncAPI asyncApi) {
        Map<String, Object> headers = new LinkedHashMap<>();
        header(headers, EVENT_TYPE, "The kind of event; what a consumer dispatches on.");
        header(headers, EVENT_VERSION, "The payload's schema version.");
        header(headers, AGGREGATE_TYPE, "The type of aggregate the event is about.");
        header(headers, AGGREGATE_ID, "The aggregate instance.");
        header(headers, IDEMPOTENCY_KEY, "The producer's dedup key.");
        header(headers, TRACE_ID, "The W3C trace id, when sampled.");
        header(headers, PRODUCED_AT, "When the producer emitted it, ISO-8601 UTC.");
        header(headers, messaging.getCorrelationHeader(), "The correlation id of the request that caused it.");
        asyncApi.getComponents().getSchemas().put(ENVELOPE_SCHEMA, SchemaObject.builder()
                .title(ENVELOPE_SCHEMA).type("object")
                .description("The platform envelope, carried on every record.")
                .properties(headers).build());
        asyncApi.getComponents().getSchemas().keySet().removeIf(name -> name.startsWith(SPRING_TYPE_HEADERS));
        asyncApi.getComponents().getMessages().values().forEach(message -> {
            if (message instanceof MessageObject object) {
                object.setHeaders(MessageHeaders.of(MessageReference.toSchema(ENVELOPE_SCHEMA)));
            }
        });
    }

    private static void header(Map<String, Object> headers, String name, String description) {
        headers.put(name, SchemaObject.builder().type("string").description(description).build());
    }

    private Optional<String> consumerNameOf(KafkaListener listener) {
        String factoryName = listener.containerFactory().isBlank()
                ? "kafkaListenerContainerFactory" : beans.resolveEmbeddedValue(listener.containerFactory());
        if (!beans.containsBean(factoryName)) {
            return Optional.empty();
        }
        return builder.consumerNameOf(beans.getBean(factoryName, KafkaListenerContainerFactory.class));
    }

    /**
     * The route an event type is published with: its declared route by name, else what the resolver does by
     * configuration - the first route listing the type, else the default route.
     */
    private Optional<OutboxProperties.Route> routeOf(String eventType, OutboxEventTypes.Declaration declared) {
        if (declared.route() != null) {
            return Optional.ofNullable(outbox.getRoutes().get(declared.route()));
        }
        return outbox.getRoutes().values().stream()
                .filter(route -> route.getEventTypes().contains(eventType))
                .findFirst()
                .or(() -> Optional.ofNullable(outbox.getDefaultRoute()));
    }

    /** The cluster a transport writes to, or {@code null} for a transport that is not Kafka. */
    private static String clusterOf(String transport) {
        if (transport == null) {
            return null;
        }
        String upper = transport.toUpperCase(Locale.ROOT);
        if (upper.equals(KAFKA)) {
            return KafkaClusters.DEFAULT;
        }
        return upper.startsWith(KAFKA_CLUSTER) ? transport.substring(KAFKA_CLUSTER.length()).toLowerCase(Locale.ROOT)
                : null;
    }
}
