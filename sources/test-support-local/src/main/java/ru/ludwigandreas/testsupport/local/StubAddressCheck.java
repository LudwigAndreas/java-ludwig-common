package ru.ludwigandreas.testsupport.local;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.StubServersInitializer;

/**
 * Refuses to start a local application that has a stub nothing would ever call.
 *
 * <p>A stub publishes its address under a property - a rest client's {@code base-url}, or a property
 * of the service's own. If no other source configures that property, the service has no such partner:
 * the stub is what a renamed client or a renamed property leaves behind, and it would sit there
 * answering nobody while the real partner is called at its new name. Saying so at start-up is cheaper
 * than someone later wondering why their fault switch does nothing.
 *
 * <h2>Why this cannot be read from the environment as it stands</h2>
 *
 * <p>The stub's own property source sets the property, so asking the environment whether it is set
 * always answers yes. The question is put to every source <em>except</em> the stubs': a real partner
 * has an address of its own there, even if it is only a placeholder with a default. An empty value
 * counts as not configured - {@code ${CHAT_BASE_URL:}} with nothing set is a partner that is switched
 * off, not one that exists.
 *
 * <p>A stub with no address property is exempt. Nothing in configuration is supposed to name it.
 */
final class StubAddressCheck implements SmartInitializingSingleton {

    private final Map<String, PartnerStub> declaredStubs;
    private final ConfigurableEnvironment environment;

    StubAddressCheck(Map<String, PartnerStub> declaredStubs, ConfigurableEnvironment environment) {
        this.declaredStubs = declaredStubs;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The local application cannot start: "
                    + String.join(" ", problems));
        }
    }

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        declaredStubs.forEach((name, stub) -> stub.address().propertyFor(name).ifPresent(property -> {
            if (!configuredOutsideStubs(property)) {
                problems.add(describe(name, stub, property));
            }
        }));
        return problems;
    }

    private static String describe(String name, PartnerStub stub, String property) {
        String what = stub.address().isRestClient()
                ? "stands in for rest client '" + name + "', which does not exist"
                : "publishes its address as " + property + ", which the service does not configure";
        return "Partner stub " + stub.getClass().getName() + " " + what + ": nothing but the stub itself "
                + "sets " + property + ". A real partner has an address of its own in the service's "
                + "configuration, even a placeholder.";
    }

    // CHECKSTYLE.OFF: IllegalCatch - an unresolvable placeholder surfaces as whatever the binder
    // wraps it in; any failure to read the value still means some source names the property.
    private boolean configuredOutsideStubs(String property) {
        List<PropertySource<?>> others = environment.getPropertySources().stream()
                .filter(source -> !StubServersInitializer.PROPERTY_SOURCE_NAME.equals(source.getName()))
                // Boot attaches a view over all the other sources as a source of its own; reading
                // it would read the stubs' values back in through the side door.
                .filter(source -> !ConfigurationPropertySources.isAttachedConfigurationPropertySource(source))
                .toList();
        try {
            return new Binder(ConfigurationPropertySources.from(others),
                    new PropertySourcesPlaceholdersResolver(others))
                    .bind(ConfigurationPropertyName.adapt(property, '.'), Bindable.of(String.class))
                    .map(value -> !value.isBlank())
                    .orElse(false);
        } catch (RuntimeException unresolved) {
            return true;
        }
    }
    // CHECKSTYLE.ON: IllegalCatch
}
