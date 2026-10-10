package ru.ludwigandreas.notification.architecture;

import ru.ludwigandreas.notification.local.NotificationStubs;
import ru.ludwigandreas.testsupport.local.rules.LocalRunRulesTest;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;

/**
 * The rules every service with a local application keeps, enabled for this one.
 *
 * <p>This service has no {@code @LudwigRestClient}, so the client-implies-stub rule has nothing to
 * check here today and starts checking the day one is added. Its partners are covered by
 * {@link OutboundHttpRulesTest} instead. The second rule is live now: anything that uses the launcher
 * lives in the {@code local} package.
 */
class LocalRunArchitectureTest extends LocalRunRulesTest {

    @Override
    protected String basePackage() {
        return "ru.ludwigandreas.notification";
    }

    @Override
    protected StubCatalog catalog() {
        return new NotificationStubs();
    }
}
