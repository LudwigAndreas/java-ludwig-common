package ru.ludwigandreas.testsupport.local.stub;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Returns every partner stub to its started state after each test method.
 *
 * <p>A fault or a one-off answer left behind by one test answers the next test's request, and that
 * test then passes or fails for a reason it never configured. The scope of anything a test does to a
 * stub is therefore the method that did it.
 */
public final class PartnerStubsResetExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        SpringExtension.getApplicationContext(context)
                .getBeanProvider(StubServers.class)
                .ifAvailable(StubServers::resetAll);
    }
}
