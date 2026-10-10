package ru.ludwigandreas.testsupport.local.rules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.testsupport.local.LocalRun;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;

/**
 * The two structural rules a service with a local application has to keep.
 *
 * <p>Exposed as rules, not only as tests, so they can be evaluated against deliberately broken classes
 * - a rule nobody has seen fail is not known to work.
 */
public final class LocalRunRules {

    /**
     * The rest-client annotation, by name.
     *
     * <p>By name and not by class so that a service with a local application and no rest client can
     * still run the package rule without the rest-client starter on its classpath.
     */
    static final String REST_CLIENT_ANNOTATION = "ru.ludwigandreas.restclient.annotation.LudwigRestClient";

    private static final String LOCAL_PACKAGE = "local";

    private LocalRunRules() {
    }

    /**
     * Every {@code @LudwigRestClient} interface has a stub or a pass-through declaration.
     *
     * <p>A new client with no stand-in is a client the local application calls for real, from a
     * laptop, at whatever address the service's configuration names. The launcher refuses to start in
     * that case; this fails the build first, at the moment the interface is added.
     *
     * @param catalog the service's stub catalog
     * @return the rule
     */
    public static ArchRule everyRestClientHasAStubOrPassThrough(StubCatalog catalog) {
        Set<String> covered = new LinkedHashSet<>(catalog.passThrough());
        catalog.stubs().stream()
                .filter(stub -> stub.address().isRestClient())
                .map(PartnerStub::name)
                .forEach(covered::add);
        return classes()
                .that().areInterfaces()
                .and().areAnnotatedWith(REST_CLIENT_ANNOTATION)
                .should(haveAStandInAmong(covered))
                .allowEmptyShould(true)
                .because("a rest client without a partner stub is called for real by the local "
                        + "application; add a PartnerStub to " + catalog.getClass().getSimpleName()
                        + " or name the client in its passThrough()");
    }

    /**
     * Anything that uses {@link LocalRun} lives in a package named {@code local}.
     *
     * <p>One known place per service for the code that starts it with stand-ins, so that nobody has to
     * search for it and nothing else grows a dependency on the launcher.
     *
     * @return the rule
     */
    public static ArchRule localApplicationsLiveInALocalPackage() {
        return classes()
                .that(useTheLauncher())
                .should(resideInAPackageNamedLocal())
                .allowEmptyShould(true)
                .because("a service's local application belongs in src/test, in a package named '"
                        + LOCAL_PACKAGE + "'");
    }

    private static ArchCondition<JavaClass> haveAStandInAmong(Set<String> covered) {
        return new ArchCondition<>("have a partner stub or a pass-through declaration") {
            @Override
            public void check(JavaClass restClient, ConditionEvents events) {
                String clientName = clientName(restClient);
                if (!covered.contains(clientName)) {
                    events.add(SimpleConditionEvent.violated(restClient, restClient.getName()
                            + " is rest client '" + clientName + "', which has no partner stub and no "
                            + "pass-through declaration. Covered clients: " + covered));
                }
            }
        };
    }

    /**
     * The name a client goes by, read the way the starter's registrar reads it: {@code name} wins over
     * {@code value}. A placeholder is compared as written, which is the conservative reading.
     */
    private static String clientName(JavaClass restClient) {
        JavaAnnotation<JavaClass> annotation = restClient.getAnnotationOfType(REST_CLIENT_ANNOTATION);
        return text(annotation.get("name")).or(() -> text(annotation.get("value"))).orElse("");
    }

    private static Optional<String> text(Optional<Object> value) {
        return value.map(Object::toString).filter(text -> !text.isBlank());
    }

    private static DescribedPredicate<JavaClass> useTheLauncher() {
        return new DescribedPredicate<>("use LocalRun") {
            @Override
            public boolean test(JavaClass candidate) {
                return candidate.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> dependency.getTargetClass().isEquivalentTo(LocalRun.class));
            }
        };
    }

    private static ArchCondition<JavaClass> resideInAPackageNamedLocal() {
        return new ArchCondition<>("reside in a package named '" + LOCAL_PACKAGE + "'") {
            @Override
            public void check(JavaClass candidate, ConditionEvents events) {
                String packageName = candidate.getPackageName();
                String lastSegment = packageName.substring(packageName.lastIndexOf('.') + 1);
                if (!LOCAL_PACKAGE.equals(lastSegment)) {
                    events.add(SimpleConditionEvent.violated(candidate, candidate.getName()
                            + " uses LocalRun from package " + packageName));
                }
            }
        };
    }
}
