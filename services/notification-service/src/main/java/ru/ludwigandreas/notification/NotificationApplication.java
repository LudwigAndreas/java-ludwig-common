package ru.ludwigandreas.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The platform's notification service: every other service asks this one to notify somebody instead
 * of talking to a mail server itself.
 *
 * <p>Two ingresses converge on one application service - a Kafka consumer for fire-and-forget traffic
 * and a REST API for synchronous and operator-initiated sends. A request fans out into one delivery
 * per recipient per channel, held in a Postgres work queue; a poller claims due deliveries with
 * {@code FOR UPDATE SKIP LOCKED}, renders a FreeMarker template and hands the result to a channel.
 *
 * <p>Assembled almost entirely from this repository's own modules: db-core, web-core, security,
 * identity-projection, odata-filter, hot-reload, observability and outbox. What it adds
 * are the two capabilities none of them provide yet - consumer-side idempotency and a distributed
 * lock - both behind narrow interfaces, and both candidates for promotion into a starter. See the
 * README.
 */
@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
