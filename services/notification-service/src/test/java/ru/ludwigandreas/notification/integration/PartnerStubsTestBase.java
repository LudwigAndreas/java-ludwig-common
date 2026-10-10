package ru.ludwigandreas.notification.integration;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.notification.local.NotificationStubs;
import ru.ludwigandreas.testsupport.local.stub.WithPartnerStubs;

/**
 * {@link NotificationTestBase} with the service's partner stand-ins started and both HTTP channels on.
 *
 * <h2>Why a shared base and not the annotations on each test</h2>
 *
 * <p>Spring caches a test context per distinct configuration and never closes it during the run. Each
 * one holds a connection pool, this service's pool is 20 connections, and the PostgreSQL container
 * accepts 100 - so a suite can afford about five contexts, and the existing tests already use four.
 * Two tests with two slightly different property sets are two more contexts and a suite that dies of
 * {@code too many clients} somewhere unrelated. Every test that needs the stand-ins therefore extends
 * this class and gets the <em>same</em> context, and that context keeps a small pool.
 *
 * <p>The read timeout is shortened here for every subclass; the test that depends on it says so.
 */
@AutoConfigureMockMvc
@WithPartnerStubs(NotificationStubs.class)
@TestPropertySource(properties = {
        "ludwig.notification.channels.chat.enabled=true",
        // The service's own default is empty. A deployment sets it; the stand-in overrides it here.
        "ludwig.notification.channels.chat.base-url=https://chat.invalid",
        "ludwig.notification.channels.chat.api-token=test-chat-token",
        "ludwig.notification.channels.webhook.enabled=true",
        "ludwig.notification.channels.webhook.signing-secret=test-webhook-secret",
        "ludwig.notification.channels.webhook.read-timeout=500ms",
        "ludwig.local.stubs.webhooks.slow-delay=5s",
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1"
})
abstract class PartnerStubsTestBase extends NotificationTestBase {
}
