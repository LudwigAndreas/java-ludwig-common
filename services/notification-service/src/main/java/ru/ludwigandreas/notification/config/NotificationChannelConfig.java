package ru.ludwigandreas.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;
import ru.ludwigandreas.notification.service.channel.InternalChatChannel;
import ru.ludwigandreas.notification.service.channel.NotificationChannel;
import ru.ludwigandreas.notification.service.channel.SmtpEmailChannel;
import ru.ludwigandreas.notification.service.channel.WebhookChannel;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * Builds the three shipped channels.
 *
 * <p>Adding a fourth is adding a {@link NotificationChannel} bean, here or anywhere else in the
 * context - nothing in the dispatch path enumerates channels or switches on a type. See "How to add a
 * channel" in the README.
 *
 * <h2>Timeouts are not optional</h2>
 *
 * <p>Every HTTP client below carries an explicit connect <em>and</em> read timeout. A provider that
 * accepts a connection and then never answers is the worst failure mode for a work queue: with no read
 * timeout the dispatch thread is gone, the lease expires, the sweeper re-queues the delivery, and the
 * next attempt hangs the same way - until every scheduler thread is stuck and the queue stops
 * entirely, without a single error being logged.
 *
 * <p>The same is true of SMTP, whose timeouts belong to {@code spring.mail.properties.mail.smtp.*}
 * and are set in {@code application.yml}; {@code NotificationConfigurationValidator} reads them back
 * and refuses to start if the lease could not outlast them.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationChannelConfig {

    /**
     * SMTP.
     *
     * <p>Conditional on the channel being enabled rather than on a mail sender existing: Spring Boot
     * autoconfigures a {@code JavaMailSender} whenever {@code spring.mail.host} is set, and a deployment
     * that has a mail host configured for something else should not silently acquire an email channel.
     */
    @Bean
    @ConditionalOnProperty(prefix = "ludwig.notification.channels.email", name = "enabled",
            matchIfMissing = true)
    public NotificationChannel smtpEmailChannel(JavaMailSender mailSender,
                                                NotificationProperties properties) {
        return new SmtpEmailChannel(mailSender, properties.getChannels().getEmail());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ludwig.notification.channels.chat", name = "enabled",
            havingValue = "true")
    public NotificationChannel internalChatChannel(RestClient.Builder builder,
                                                   ObjectMapper objectMapper,
                                                   NotificationProperties properties) {
        NotificationProperties.Chat chat = properties.getChannels().getChat();
        RestClient client = builder.clone()
                .baseUrl(chat.getBaseUrl())
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(chat.getConnectTimeout())
                        .withReadTimeout(chat.getReadTimeout())))
                .build();
        return new InternalChatChannel(client, objectMapper, chat);
    }

    /**
     * Signed outbound webhooks.
     *
     * <p>No base URL: the destination comes from each recipient's own profile, so the client is built
     * for its timeouts alone and every request carries an absolute URI. {@code WebhookChannel} checks
     * the scheme before calling, because a destination that comes from data can say anything.
     */
    @Bean
    @ConditionalOnProperty(prefix = "ludwig.notification.channels.webhook", name = "enabled",
            havingValue = "true")
    public NotificationChannel webhookChannel(RestClient.Builder builder,
                                              ObjectMapper objectMapper,
                                              NotificationProperties properties) {
        NotificationProperties.Webhook webhook = properties.getChannels().getWebhook();
        RestClient client = builder.clone()
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(webhook.getConnectTimeout())
                        .withReadTimeout(webhook.getReadTimeout())))
                .build();
        return new WebhookChannel(client, objectMapper, webhook);
    }
}
