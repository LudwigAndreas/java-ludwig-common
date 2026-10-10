package ru.ludwigandreas.testsupport.local.architecture;

import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;
import ru.ludwigandreas.testsupport.local.rules.LocalRunRulesTest;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;

/** The sample application keeping the rules, the way a service enables them. */
class SampleLocalRunRulesTest extends LocalRunRulesTest {

    @Override
    protected String basePackage() {
        return "ru.ludwigandreas.testsupport.local.integration.sample";
    }

    @Override
    protected StubCatalog catalog() {
        return new SampleStubs();
    }
}
