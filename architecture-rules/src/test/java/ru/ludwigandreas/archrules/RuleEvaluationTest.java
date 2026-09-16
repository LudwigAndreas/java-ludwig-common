package ru.ludwigandreas.archrules;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.ludwigandreas.archrules.rules.ConfigurationAccessRules;
import ru.ludwigandreas.archrules.rules.ConfigurationPropertiesRules;
import ru.ludwigandreas.archrules.rules.CycleRules;
import ru.ludwigandreas.archrules.rules.DtoImmutabilityRules;
import ru.ludwigandreas.archrules.rules.EntityBaseRules;
import ru.ludwigandreas.archrules.rules.ExceptionRules;
import ru.ludwigandreas.archrules.rules.KafkaContractRules;
import ru.ludwigandreas.archrules.rules.MapperConventionRules;
import ru.ludwigandreas.archrules.rules.OptionalUsageRules;
import ru.ludwigandreas.archrules.rules.RestPathRules;
import ru.ludwigandreas.archrules.rules.DomainIsolationRules;
import ru.ludwigandreas.archrules.rules.KafkaRules;
import ru.ludwigandreas.archrules.rules.LayeringRules;
import ru.ludwigandreas.archrules.rules.ModuleBoundaryRules;
import ru.ludwigandreas.archrules.rules.PersistenceRules;
import ru.ludwigandreas.archrules.rules.SpringWiringRules;
import ru.ludwigandreas.archrules.rules.StorageRules;
import ru.ludwigandreas.archrules.rules.TestSeparationRules;
import ru.ludwigandreas.archrules.rules.WebRules;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each rule is proven from both sides: it stays quiet on the compliant fixture service and it fires
 * on the fixture that breaks it. A rule that only ever passes proves nothing.
 */
class RuleEvaluationTest {

    @Test
    @DisplayName("the compliant service satisfies every rule, opt-in rules included")
    void compliantServicePassesEverything() {
        assertThat(Fixtures.failingRuleIds(compliantService().build())).isEmpty();
    }

    /** The compliant fixture, with the names only a consuming service can supply. */
    private static ArchitectureRulesConfiguration.Builder compliantService() {
        return Fixtures.withSharedTypes(Fixtures.configurationFor("good")
                        .enable("domain-isolation",
                                StorageRules.ADAPTERS_DO_NOT_EXPOSE_SDK_TYPES.value(),
                                ModuleBoundaryRules.CROSS_MODULE_ACCESS_THROUGH_API.value()),
                "good",
                "catalog.repository.entity.BaseEntity",
                "catalog.support.ApplicationException",
                "catalog.service.ProductEventPublisher");
    }

    @Test
    @DisplayName("the compliant service is actually checked by every group")
    void compliantServiceResolvesEveryGroup() {
        Set<String> resolved = Fixtures.resolvedRuleIds(compliantService().build());

        assertThat(resolved).contains(
                LayeringRules.LAYERED_ARCHITECTURE.value(),
                CycleRules.MODULES.value(),
                DomainIsolationRules.FREE_OF_SPRING.value(),
                WebRules.CONTROLLERS_DO_NOT_EXPOSE_ENTITIES.value(),
                PersistenceRules.ENTITIES_IN_ENTITY_PACKAGES.value(),
                KafkaRules.CLIENTS_ARE_CONFINED.value(),
                StorageRules.SDK_IS_CONFINED.value(),
                SpringWiringRules.CONSTRUCTOR_INJECTION.value(),
                ModuleBoundaryRules.INTERNALS_ARE_PRIVATE.value(),
                TestSeparationRules.NO_TEST_FRAMEWORKS_IN_PRODUCTION.value(),
                ConfigurationAccessRules.ENVIRONMENT_ACCESS_IS_CONFINED.value(),
                ExceptionRules.EXCEPTIONS_EXTEND_BASE.value(),
                DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value(),
                EntityBaseRules.ENTITIES_EXTEND_BASE.value(),
                KafkaContractRules.PRODUCERS_IMPLEMENT_PUBLISHER.value(),
                RestPathRules.CONTROLLERS_DECLARE_VERSIONED_BASE_PATH.value(),
                ConfigurationPropertiesRules.PROPERTIES_ARE_VALIDATED.value(),
                OptionalUsageRules.NOT_A_FIELD_TYPE.value(),
                MapperConventionRules.MAPPERS_ARE_MAPSTRUCT_INTERFACES.value(),
                SpringWiringRules.SINGLETON_BEANS_ARE_STATELESS.value());
    }

    @Test
    @DisplayName("exception architecture violations are reported")
    void exceptionViolationsAreReported() {
        ArchitectureRulesConfiguration configuration = Fixtures.withSharedTypes(
                Fixtures.configurationFor("bad.exceptions"), "bad.exceptions",
                "catalog.repository.entity.BaseEntity",
                "catalog.support.ApplicationException",
                "catalog.messaging.EventPublisher").build();

        assertThat(Fixtures.failingRuleIds(configuration)).contains(
                ExceptionRules.EXCEPTIONS_EXTEND_BASE.value(),
                ExceptionRules.CONTROLLERS_DO_NOT_CATCH_CHECKED.value(),
                ExceptionRules.LISTENERS_DO_NOT_CATCH_CHECKED.value());
    }

    @Test
    @DisplayName("a rule needing a name the service has not configured says so instead of passing")
    void unconfiguredBaseTypesAreReportedAsMisconfiguration() {
        Set<String> failing = Fixtures.failingRuleIds(Fixtures.configurationFor("bad.entities").build());

        assertThat(failing).contains(EntityBaseRules.ENTITIES_EXTEND_BASE.value());
        assertThat(Fixtures.ruleReport(Fixtures.configurationFor("bad.entities").build(),
                EntityBaseRules.ENTITIES_EXTEND_BASE).violations())
                .anySatisfy(violation -> assertThat(violation.message())
                        .contains("No base-entity type is configured")
                        .contains("architecture.rules.conventions.types.base-entity"));
    }

    @Test
    @DisplayName("a service with nothing to check is not asked to configure a base type")
    void unconfiguredBaseTypesStayQuietWhenNothingMatches() {
        // bad.cycles has no entity at all, so the entity rule has nothing to complain about
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.cycles").build()))
                .doesNotContain(EntityBaseRules.ENTITIES_EXTEND_BASE.value());
    }

    @Test
    void entitiesNotExtendingTheBaseAreReported() {
        ArchitectureRulesConfiguration configuration = Fixtures.configurationFor("bad.entities")
                .conventions(conventions -> conventions.types(TypeRole.BASE_ENTITY,
                        Fixtures.ROOT + ".bad.entities.catalog.repository.entity.BaseEntity"))
                .build();

        assertThat(Fixtures.failingRuleIds(configuration))
                .contains(EntityBaseRules.ENTITIES_EXTEND_BASE.value());
    }

    @Test
    void mutableApiModelsAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.dtos").build()))
                .contains(DtoImmutabilityRules.NO_SETTERS_ON_API_MODELS.value());
    }

    @Test
    void producersOutsideTheSharedContractAreReported() {
        ArchitectureRulesConfiguration configuration = Fixtures.configurationFor("bad.contracts")
                .conventions(conventions -> conventions.types(TypeRole.EVENT_PUBLISHER,
                        Fixtures.ROOT + ".bad.contracts.catalog.messaging.EventPublisher"))
                .build();

        assertThat(Fixtures.failingRuleIds(configuration))
                .contains(KafkaContractRules.PRODUCERS_IMPLEMENT_PUBLISHER.value());
    }

    @Test
    void unversionedRestPathsAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.paths").build()))
                .contains(RestPathRules.CONTROLLERS_DECLARE_VERSIONED_BASE_PATH.value());

        // the pattern itself is configuration, not a hardcoded convention: widening it accepts the
        // path the organisation would otherwise reject, while the controller that declares no base
        // path at all still fails
        assertThat(Fixtures.ruleReport(Fixtures.configurationFor("bad.paths")
                        .conventions(conventions -> conventions.settings(
                                ConventionSetting.REST_BASE_PATH_PATTERN, "/(api/v\\d+|orders)(/.*)?"))
                        .build(),
                RestPathRules.CONTROLLERS_DECLARE_VERSIONED_BASE_PATH).violations())
                .singleElement()
                .satisfies(violation -> assertThat(violation.className()).endsWith("UnmappedController"));
    }

    @Test
    void unvalidatedConfigurationPropertiesAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.properties").build()))
                .contains(ConfigurationPropertiesRules.PROPERTIES_ARE_VALIDATED.value());
    }

    @Test
    void optionalFieldsAndParametersAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.optionals").build())).contains(
                OptionalUsageRules.NOT_A_FIELD_TYPE.value(),
                OptionalUsageRules.NOT_A_PARAMETER_TYPE.value());
    }

    @Test
    void handWrittenMappersAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.mappers").build()))
                .contains(MapperConventionRules.MAPPERS_ARE_MAPSTRUCT_INTERFACES.value());
    }

    @Test
    @DisplayName("mutable state is reported on every singleton stereotype, advice and configuration included")
    void mutableSingletonStateIsReported() {
        Set<String> failing = Fixtures.failingRuleIds(Fixtures.configurationFor("bad.singletons").build());

        assertThat(failing).contains(SpringWiringRules.SINGLETON_BEANS_ARE_STATELESS.value());
        assertThat(Fixtures.ruleReport(Fixtures.configurationFor("bad.singletons").build(),
                SpringWiringRules.SINGLETON_BEANS_ARE_STATELESS).violations())
                .extracting(violation -> violation.className())
                .contains(Fixtures.ROOT + ".bad.singletons.catalog.service.StatefulService",
                        Fixtures.ROOT + ".bad.singletons.catalog.config.StatefulConfiguration",
                        Fixtures.ROOT + ".bad.singletons.catalog.web.StatefulAdvice");
    }

    @Test
    void controllerBoundaryViolationsAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.boundary").build())).contains(
                LayeringRules.LAYERED_ARCHITECTURE.value(),
                LayeringRules.CONTROLLERS_DO_NOT_ACCESS_PERSISTENCE.value(),
                WebRules.CONTROLLERS_DO_NOT_EXPOSE_ENTITIES.value(),
                WebRules.CONTROLLERS_DO_NOT_USE_PERSISTENCE_TYPES.value(),
                WebRules.CONTROLLERS_DO_NOT_CALL_CONTROLLERS.value(),
                WebRules.DTOS_ARE_NOT_ENTITIES.value());
    }

    @Test
    void persistenceViolationsAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.datastore").build())).contains(
                PersistenceRules.ENTITIES_IN_ENTITY_PACKAGES.value(),
                PersistenceRules.REPOSITORIES_ARE_INTERFACES.value(),
                PersistenceRules.PERSISTENCE_CONTEXT_IS_CONFINED.value());
    }

    @Test
    void kafkaViolationsAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.broker").build())).contains(
                KafkaRules.CLIENTS_ARE_CONFINED.value(),
                KafkaRules.CONSUMERS_IN_MESSAGING_PACKAGES.value(),
                KafkaRules.CONSUMERS_DO_NOT_USE_REPOSITORIES.value(),
                KafkaRules.PAYLOADS_ARE_FREE_OF_JPA.value(),
                KafkaRules.PAYLOADS_ARE_NOT_REST_DTOS.value(),
                KafkaRules.MESSAGING_DOES_NOT_DEPEND_ON_CONTROLLERS.value());
    }

    @Test
    void storageSdkLeakIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.objectstore").build()))
                .contains(StorageRules.SDK_IS_CONFINED.value())
                // the adapter's own signature is only checked once the opt-in rule is asked for
                .doesNotContain(StorageRules.ADAPTERS_DO_NOT_EXPOSE_SDK_TYPES.value());

        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.objectstore")
                .enable(StorageRules.ADAPTERS_DO_NOT_EXPOSE_SDK_TYPES.value())
                .build()))
                .contains(StorageRules.ADAPTERS_DO_NOT_EXPOSE_SDK_TYPES.value());
    }

    @Test
    void springWiringViolationsAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.spring").build())).contains(
                SpringWiringRules.CONSTRUCTOR_INJECTION.value(),
                SpringWiringRules.CONFIGURATION_IN_CONFIG_PACKAGES.value(),
                SpringWiringRules.TRANSACTIONAL_CLASSES.value(),
                SpringWiringRules.TRANSACTIONAL_METHODS.value());
    }

    @Test
    void environmentAccessOutsideConfigurationIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.environment").build()))
                .contains(ConfigurationAccessRules.ENVIRONMENT_ACCESS_IS_CONFINED.value());
    }

    @Test
    void domainIsolationIsOptInButFiresWhenEnabled() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.hexagonal").build()))
                .doesNotContain(DomainIsolationRules.FREE_OF_SPRING.value());

        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.hexagonal")
                .enable("domain-isolation")
                .build())).contains(
                DomainIsolationRules.FREE_OF_SPRING.value(),
                DomainIsolationRules.FREE_OF_PERSISTENCE.value(),
                DomainIsolationRules.FREE_OF_JSON.value());
    }

    @Test
    void reachingIntoAnotherModulesInternalsIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.modules").build()))
                .contains(ModuleBoundaryRules.INTERNALS_ARE_PRIVATE.value());
    }

    @Test
    void moduleCyclesAreReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.cycles").build()))
                .contains(CycleRules.MODULES.value());
    }

    @Test
    void testFrameworkUsageInProductionCodeIsReported() {
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.tests").build()))
                .contains(TestSeparationRules.NO_TEST_FRAMEWORKS_IN_PRODUCTION.value());
    }
}
