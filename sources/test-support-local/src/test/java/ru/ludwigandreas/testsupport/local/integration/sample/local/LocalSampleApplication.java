package ru.ludwigandreas.testsupport.local.integration.sample.local;

import ru.ludwigandreas.testsupport.local.LocalRun;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleApplication;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleFixtures;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;

/**
 * The sample's local application, in the shape a service's is meant to take.
 *
 * <p>Runnable as it stands: from the IDE, or with
 * {@code mvn -pl :test-support-local spring-boot:test-run}. It lives in a package named {@code local},
 * which is where the build-time rule requires anything that uses {@link LocalRun} to be.
 */
public final class LocalSampleApplication {

    private LocalSampleApplication() {
    }

    public static void main(String[] args) {
        LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .fixtures(SampleFixtures.class)
                .run(args);
    }
}
