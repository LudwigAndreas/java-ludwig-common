package ru.ludwigandreas.testsupport.local.integration.sample;

import java.util.List;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;

/** The sample's stub catalog: one client, one stub. */
public class SampleStubs implements StubCatalog {

    @Override
    public List<PartnerStub> stubs() {
        return List.of(new PartnerApiStub());
    }
}
