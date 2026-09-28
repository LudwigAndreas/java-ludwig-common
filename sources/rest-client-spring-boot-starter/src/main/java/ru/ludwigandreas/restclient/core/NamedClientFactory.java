package ru.ludwigandreas.restclient.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import ru.ludwigandreas.restclient.config.ClientMode;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.TransportEngine;
import ru.ludwigandreas.restclient.error.ReactiveErrorTranslator;
import ru.ludwigandreas.restclient.error.TranslatingResponseErrorHandler;
import ru.ludwigandreas.restclient.observability.LudwigClientRequestObservationConvention;
import ru.ludwigandreas.restclient.observability.LudwigWebClientObservationConvention;
import ru.ludwigandreas.restclient.transport.ApacheTransport;
import ru.ludwigandreas.restclient.transport.ConnectionPoolGauges;
import ru.ludwigandreas.restclient.transport.JdkTransport;
import ru.ludwigandreas.restclient.transport.ReactorNettyTransport;
import ru.ludwigandreas.restclient.transport.Transport;
import ru.ludwigandreas.restclient.spi.LudwigRestClientCustomizer;
import ru.ludwigandreas.restclient.spi.LudwigWebClientCustomizer;

/**
 * Assembles one named client: transport, pipeline, serialization, observation, error handling, and
 * finally the customizer beans.
 *
 * <p>The order in {@link #buildRest} and {@link #buildWebClient} <em>is</em> the precedence order the
 * README documents. Everything derived from properties is applied first and the customizers run
 * last, which is what makes a customizer an override rather than a default that something else may
 * still overwrite.
 */
public class NamedClientFactory implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NamedClientFactory.class);

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final ObservationRegistry observationRegistry;
    private final List<LudwigRestClientCustomizer> restCustomizers;
    private final List<LudwigWebClientCustomizer> webCustomizers;
    private final List<ClientHttpRequestInterceptor> sharedInterceptors;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final boolean publishPoolMetrics;

    /**
     * How to release each built transport at shutdown.
     *
     * <p>A connection pool owns sockets and an eviction thread, and neither is released by garbage
     * collection: an un-closed pool turns a rolling restart into a slow leak of both on the machine.
     * This factory is a bean, so Spring calls {@link #destroy()}; the transports themselves are not
     * beans and would otherwise have nobody to close them.
     */
    private final List<Runnable> closers = new CopyOnWriteArrayList<>();

    /** Creates the factory from the collaborators every client it builds will share. */
    // CHECKSTYLE.OFF: ParameterNumber - a factory that assembles every layer of a client needs one
    // collaborator per layer; a builder here would only move the list.
    public NamedClientFactory(ObjectMapper objectMapper, ResourceLoader resourceLoader,
                              ObservationRegistry observationRegistry,
                              List<LudwigRestClientCustomizer> restCustomizers,
                              List<LudwigWebClientCustomizer> webCustomizers,
                              List<ClientHttpRequestInterceptor> sharedInterceptors,
                              io.micrometer.core.instrument.MeterRegistry meterRegistry,
                              boolean publishPoolMetrics) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.observationRegistry = observationRegistry;
        this.restCustomizers = restCustomizers;
        this.webCustomizers = webCustomizers;
        this.sharedInterceptors = sharedInterceptors;
        this.meterRegistry = meterRegistry;
        this.publishPoolMetrics = publishPoolMetrics;
    }
    // CHECKSTYLE.ON: ParameterNumber

    /** Builds the blocking client called {@code name}. */
    public RestClient buildRest(ClientRuntime runtime) {
        ClientProperties props = runtime.getProperties();
        Transport transport = transport(runtime.getName(), props);
        bindPoolGauges(runtime.getName(), transport.poolHandle());
        if (transport.closer() != null) {
            closers.add(transport.closer());
        }

        boolean decodeGzip = props.getTransport() == TransportEngine.HTTP_CLIENT
                && Boolean.TRUE.equals(props.getCompression());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                // A URI builder factory that notes the template it expanded, so the metric tag, the
                // audit field and the exception message quote /invoices/{id} rather than a path with
                // a customer id in it - even in a service with no observation handlers at all.
                .uriBuilderFactory(new TemplateCapturingUriBuilderFactory(props.getBaseUrl()))
                .requestFactory(new PipelineClientHttpRequestFactory(runtime, transport.factory(),
                        decodeGzip))
                .observationRegistry(observationRegistry)
                .observationConvention(new LudwigClientRequestObservationConvention(runtime.getName()))
                .defaultStatusHandler(new TranslatingResponseErrorHandler(runtime.getName(),
                        runtime.getErrorTranslation(), runtime.getRedactor(), runtime.getCallContext(),
                        props.getLogging().getMaxBodySize()))
                .defaultHeaders(headers -> applyDefaultHeaders(headers, runtime, decodeGzip));

        sharedInterceptors.forEach(builder::requestInterceptor);
        applyMessageConverters(runtime, builder);

        // Last, and deliberately so: a customizer is the override level, and anything applied after
        // it would silently win over a service's explicit decision.
        for (LudwigRestClientCustomizer customizer : restCustomizers) {
            if (customizer.supports(runtime.getName())) {
                customizer.customize(runtime.getName(), builder);
            }
        }
        log.info("REST client '{}' ready: {} {} via {}, connect {}, read {}, auth {}",
                runtime.getName(), props.getMode(), props.getBaseUrl(), props.getTransport(),
                props.getConnectTimeout(), props.getReadTimeout(),
                runtime.getAuthenticator().describe());
        return builder.build();
    }

    /** Builds the reactive client called {@code name}. */
    public WebClient buildWebClient(ClientRuntime runtime) {
        ClientProperties props = runtime.getProperties();
        ReactiveErrorTranslator translator = new ReactiveErrorTranslator(runtime.getName(),
                runtime.getErrorTranslation(), runtime.getRedactor(), runtime.getCallContext(),
                props.getLogging().getMaxBodySize());

        ReactorNettyTransport.ReactiveTransport transport = ReactorNettyTransport.create(
                runtime.getName(), props, resourceLoader, publishPoolMetrics);
        closers.add(transport.closer());

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .clientConnector(transport.connector())
                .observationRegistry(observationRegistry)
                .observationConvention(new LudwigWebClientObservationConvention(runtime.getName()))
                .defaultHeaders(headers -> applyDefaultHeaders(headers, runtime, false))
                .filter(new ReactiveCorrelationFilter(runtime.getCallContext()))
                .filter(new ReactiveExchangePipeline(runtime))
                .defaultStatusHandler(translator::isError, translator::translate);

        ObjectMapper mapper = ClientObjectMappers.forClient(runtime.getName(), objectMapper,
                props.getSerialization());
        if (mapper != objectMapper) {
            builder.codecs(codecs -> {
                codecs.defaultCodecs().jackson2JsonEncoder(
                        new org.springframework.http.codec.json.Jackson2JsonEncoder(mapper));
                codecs.defaultCodecs().jackson2JsonDecoder(
                        new org.springframework.http.codec.json.Jackson2JsonDecoder(mapper));
            });
        }

        for (LudwigWebClientCustomizer customizer : webCustomizers) {
            if (customizer.supports(runtime.getName())) {
                customizer.customize(runtime.getName(), builder);
            }
        }
        log.info("Reactive client '{}' ready: {} via reactor-netty, connect {}, read {}, auth {}",
                runtime.getName(), props.getBaseUrl(), props.getConnectTimeout(),
                props.getReadTimeout(), runtime.getAuthenticator().describe());
        return builder.build();
    }

    /** Releases every pool this factory built. */
    @Override
    public void destroy() {
        for (Runnable closer : closers) {
            try {
                closer.run();
            } catch (RuntimeException ex) {
                // One pool that will not close must not stop the others from closing.
                log.warn("A REST client transport could not be released cleanly.", ex);
            }
        }
        closers.clear();
    }

    private Transport transport(String clientName, ClientProperties props) {
        TransportEngine engine = props.getTransport();
        if (props.getMode() == ClientMode.ASYNC) {
            throw new IllegalStateException("Client '" + clientName + "' is mode=async; it has no "
                    + "blocking transport. Inject a WebClient, or use registry.reactive(\"" + clientName
                    + "\").");
        }
        return engine == TransportEngine.APACHE
                ? ApacheTransport.create(clientName, props, resourceLoader)
                : JdkTransport.create(clientName, props, resourceLoader);
    }

    private void bindPoolGauges(String clientName, Object poolHandle) {
        if (publishPoolMetrics && poolHandle != null && meterRegistry != null) {
            ConnectionPoolGauges.bind(meterRegistry, clientName, poolHandle);
        }
    }

    private void applyDefaultHeaders(HttpHeaders headers, ClientRuntime runtime, boolean requestGzip) {
        ClientProperties props = runtime.getProperties();
        props.getDefaultHeaders().forEach(headers::set);
        if (props.getUserAgent() != null) {
            headers.set(HttpHeaders.USER_AGENT, props.getUserAgent());
        }
        if (requestGzip) {
            // The JDK client neither advertises nor decodes compression, so the starter does both -
            // see GzipDecodingClientHttpResponse - which is what keeps `compression: true` meaning
            // the same thing on every engine.
            headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate");
        }
    }

    private void applyMessageConverters(ClientRuntime runtime, RestClient.Builder builder) {
        ObjectMapper mapper = ClientObjectMappers.forClient(runtime.getName(), objectMapper,
                runtime.getProperties().getSerialization());
        if (mapper == objectMapper) {
            return;
        }
        builder.messageConverters(converters -> replaceJacksonConverter(converters, mapper));
    }

    /**
     * Swaps the Jackson converter for one bound to this client's mapper, leaving every other
     * converter in place.
     *
     * <p>Replacing the whole list would drop the form, string, byte-array and multipart converters a
     * client needs for anything that is not JSON.
     */
    private void replaceJacksonConverter(List<HttpMessageConverter<?>> converters, ObjectMapper mapper) {
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof MappingJackson2HttpMessageConverter) {
                converters.set(i, new MappingJackson2HttpMessageConverter(mapper));
                return;
            }
        }
        converters.add(new MappingJackson2HttpMessageConverter(mapper));
    }
}
