package ru.ludwigandreas.outbox.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.dispatch.rest.RestOutboxDispatcher;

@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxRestAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "outboxRestClient")
    public RestClient outboxRestClient(OutboxProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getRest().getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getRest().getReadTimeout().toMillis());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "restOutboxDispatcher")
    public OutboxDispatcher restOutboxDispatcher(@Qualifier("outboxRestClient") RestClient restClient, OutboxProperties properties) {
        return new RestOutboxDispatcher(restClient, properties.getRest().getEndpoints());
    }
}
