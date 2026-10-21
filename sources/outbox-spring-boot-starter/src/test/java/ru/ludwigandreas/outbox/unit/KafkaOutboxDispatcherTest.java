package ru.ludwigandreas.outbox.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import ru.ludwigandreas.messaging.api.MessageHeaders;
import ru.ludwigandreas.outbox.dispatch.DispatchResult;
import ru.ludwigandreas.outbox.dispatch.kafka.KafkaOutboxDispatcher;
import ru.ludwigandreas.outbox.entity.OutboxMessage;

/**
 * The envelope a dispatched record carries.
 */
class KafkaOutboxDispatcherTest {

    @Test
    @DisplayName("writes every envelope header under its canonical name and none under a pre-prefix one")
    @SuppressWarnings("unchecked")
    void writesCanonicalNamesOnly() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        OutboxMessage message = new OutboxMessage();
        message.setDestination("orders");
        message.setAggregateType("Order");
        message.setAggregateId("4711");
        message.setEventType("OrderCreated");
        message.setEventVersion(2);
        message.setIdempotencyKey("created-4711");
        message.setTraceId("trace-1");
        message.setPayload("{}");

        assertThat(new KafkaOutboxDispatcher(template, Duration.ofSeconds(1)).dispatch(message))
                .isInstanceOf(DispatchResult.Success.class);

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        ProducerRecord<String, String> record = sent.getValue();
        assertThat(text(record, MessageHeaders.EVENT_TYPE)).isEqualTo("OrderCreated");
        assertThat(text(record, MessageHeaders.EVENT_VERSION)).isEqualTo("2");
        assertThat(text(record, MessageHeaders.IDEMPOTENCY_KEY)).isEqualTo("created-4711");
        assertThat(text(record, MessageHeaders.TRACE_ID)).isEqualTo("trace-1");
        // The names written before 3.0.0. Nothing reads them any more, so writing them is noise on
        // every record and a second spelling for a consumer to copy.
        assertThat(record.headers())
                .extracting(Header::key)
                .doesNotContain("event-type", "event-version", "idempotency-key", "trace-id");
    }

    private static String text(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
