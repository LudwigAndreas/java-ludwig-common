package ru.ludwigandreas.outbox.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import ru.ludwigandreas.outbox.integration.testmodel.StubOutboxDispatcher;
import ru.ludwigandreas.outbox.integration.testmodel.TransactionalPublishHelper;

@SpringBootApplication
public class OutboxTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(OutboxTestApplication.class, args);
    }

    @Bean
    public StubOutboxDispatcher stubOutboxDispatcher() {
        return new StubOutboxDispatcher();
    }

    @Bean
    public TransactionalPublishHelper transactionalPublishHelper(OutboxEventPublisher publisher) {
        return new TransactionalPublishHelper(publisher);
    }
}
