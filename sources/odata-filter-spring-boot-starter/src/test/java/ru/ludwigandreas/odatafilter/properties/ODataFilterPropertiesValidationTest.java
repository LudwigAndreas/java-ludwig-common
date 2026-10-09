package ru.ludwigandreas.odatafilter.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.odatafilter.config.ODataFilterAutoConfiguration;
import ru.ludwigandreas.odatafilter.config.ODataFilterMetricsAutoConfiguration;

/**
 * A bound value that can only be wrong fails the context rather than the first request.
 *
 * <p>Each of these was accepted before {@code ODataFilterProperties} carried any constraints. A
 * {@code max-page-size} of 0 is the clearest: every query then failed, because the resolved page size could
 * never satisfy {@code requested <= maxPageSize}, and the message named {@code $top} - pointing the operator
 * at the caller instead of at the line of YAML that caused it.
 */
class ODataFilterPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ValidationAutoConfiguration.class,
                    ODataFilterMetricsAutoConfiguration.class,
                    ODataFilterAutoConfiguration.class));

    @Test
    @DisplayName("a max page size of zero fails the context, not every later query")
    void refusesAZeroMaxPageSize() {
        runner.withPropertyValues("odata.filter.max-page-size=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        // The whole stack, not getMessage(): Boot's top-level message says only "Could not
                        // bind properties", and the field name is in the BindValidationException underneath.
                        // What matters is that an operator reading the failure is told which property.
                        .hasStackTraceContaining("maxPageSize"));
    }

    @Test
    @DisplayName("every other bound limit is refused at zero too")
    void refusesZeroForEveryLimit() {
        for (String property : new String[]{
                "odata.filter.max-depth",
                "odata.filter.default-page-size",
                "odata.filter.max-nested-property-depth",
                "odata.filter.max-expression-length"}) {
            runner.withPropertyValues(property + "=0")
                    .run(context -> assertThat(context)
                            .as("%s=0 must fail the context", property)
                            .hasFailed());
        }
    }

    @Test
    @DisplayName("a negative limit is refused as well")
    void refusesANegativeLimit() {
        runner.withPropertyValues("odata.filter.max-depth=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("the defaults are valid, so the starter works with no configuration at all")
    void acceptsTheDefaults() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(ODataFilterProperties.class));
    }

    @Test
    @DisplayName("a sensible override is accepted")
    void acceptsASensibleOverride() {
        runner.withPropertyValues(
                        "odata.filter.max-depth=6",
                        "odata.filter.max-page-size=500",
                        "odata.filter.default-page-size=50")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ODataFilterProperties properties = context.getBean(ODataFilterProperties.class);
                    assertThat(properties.getMaxDepth()).isEqualTo(6);
                    assertThat(properties.getMaxPageSize()).isEqualTo(500);
                });
    }

    @Test
    @DisplayName("defaultPageSize above maxPageSize is NOT refused, and that is deliberate")
    void doesNotCheckTheCrossFieldInvariant() {
        // A cross-field invariant needs a class-level constraint or a Validator bean, and the resolved
        // per-entity policy - where a @FilterPolicy override can change either number - is the only place
        // the comparison is meaningful. Asserted so the omission is a decision on record rather than a gap
        // someone later reads as an oversight.
        runner.withPropertyValues(
                        "odata.filter.max-page-size=10",
                        "odata.filter.default-page-size=100")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
