package ru.ludwigandreas.archrules.rules;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.PackageRole;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * The object storage SDK lives behind an adapter.
 *
 * <p>Two things follow from letting an S3 client spread through a service. Testing: {@code S3Client}
 * has to be mocked or a container started wherever it appears, instead of once behind a
 * {@code StorageService} interface. Change: an SDK major version, a switch to another provider, or
 * an added retry/encryption policy turns into a change in every class that ever uploaded a file. The
 * confinement rule keeps that cost in one package.
 *
 * <p>Configuration classes are allowed to see the SDK as well - somebody has to build the client
 * bean.
 */
public final class StorageRules implements ArchitectureRuleSet {

    /** Only the storage adapters (and the configuration that builds the client) see the SDK. */
    public static final RuleId SDK_IS_CONFINED = RuleId.of(RuleGroup.STORAGE, "sdk-is-confined-to-storage-adapters");

    /** The storage adapters do not leak SDK types through their own API. Opt-in. */
    public static final RuleId ADAPTERS_DO_NOT_EXPOSE_SDK_TYPES =
            RuleId.of(RuleGroup.STORAGE, "adapters-do-not-expose-sdk-types");

    @Override
    public RuleGroup group() {
        return RuleGroup.STORAGE;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<ArchitectureRule> rules = new ArrayList<>();
        // Confinement applies even to a service with no storage package configured: it simply has
        // nowhere the SDK would be allowed.
        rules.add(ArchitectureRule.of(SDK_IS_CONFINED, sdkIsConfined(context),
                "Depend on the service's own storage abstraction (a StorageService/DocumentStorage interface)"
                        + " and put the SDK call in the adapter that implements it, so the SDK stays swappable"
                        + " and this class stays testable without a client to mock."));
        if (context.hasPackagesFor(PackageRole.STORAGE)) {
            rules.add(ArchitectureRule.optIn(ADAPTERS_DO_NOT_EXPOSE_SDK_TYPES, adaptersDoNotExposeSdkTypes(context),
                    "Return the service's own type (bytes, a stream wrapper, a domain object) instead of an"
                            + " SDK type, otherwise the coupling has only moved one package outwards."));
        }
        return List.copyOf(rules);
    }

    private static ArchRule sdkIsConfined(RuleContext context) {
        List<String> allowed = new ArrayList<>(context.packages(PackageRole.STORAGE));
        allowed.addAll(context.packages(PackageRole.CONFIGURATION));
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingOutsideOf(allowed)
                        .as("classes outside the storage and configuration packages " + allowed))
                .should(ArchitectureConditions.notDependOnClassesThat(ConventionPredicates.storageSdk(context),
                        "the object storage SDK - go through the storage abstraction instead"))
                .as("The object storage SDK is confined to the storage adapters");
    }

    /**
     * Opt-in, because it constrains the adapter's own design rather than everyone else's: a
     * {@code getObject} that returns an SDK {@code ResponseInputStream} has moved the coupling one
     * package outwards instead of removing it, but plenty of services are fine with that.
     */
    private static ArchRule adaptersDoNotExposeSdkTypes(RuleContext context) {
        // Public methods only: the adapter is built from an SDK client, so its constructor and its
        // fields necessarily mention one. What must not leak is the API it offers the rest of the
        // service.
        return ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat(ConventionPredicates.storageAdapters(context))
                .and().arePublic()
                .should(ArchitectureConditions.notHaveSignatureTypesThat(
                        ConventionPredicates.storageSdk(context),
                        "object storage SDK types - the abstraction speaks in the service's own types"))
                .as("Storage adapters do not expose SDK types");
    }
}
