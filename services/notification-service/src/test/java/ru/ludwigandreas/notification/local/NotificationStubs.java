package ru.ludwigandreas.notification.local;

import java.util.List;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;

/**
 * Every partner notification-service has, stood in for, written down once.
 *
 * <p>The local application, the integration tests that use {@code @WithPartnerStubs}, and the
 * architecture test all read this one list. The mail server is not in it: it is not HTTP, and the
 * launcher starts it itself ({@code LocalRun.mail()}).
 *
 * <p>Adding an outbound HTTP partner to the service means adding its stub here. The service's
 * architecture test fails a new {@code RestClient} built anywhere but
 * {@code NotificationChannelConfig}, which is the class a reviewer reads beside this one.
 */
public class NotificationStubs implements StubCatalog {

    @Override
    public List<PartnerStub> stubs() {
        return List.of(new IdentityProviderStub(), new ChatApiStub(), new WebhookReceiverStub());
    }
}
