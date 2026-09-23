package ru.ludwigandreas.usersettings.api;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * How a service hands its setting definitions to the engine.
 *
 * <pre>{@code
 * @Bean
 * SettingDefinitionSource accountSettings() {
 *     return SettingDefinitionSource.of(AccountSettings.LOCALE, AccountSettings.TIMEZONE);
 * }
 * }</pre>
 *
 * <p>A bean rather than a classpath scan, and a collection rather than one bean per definition. The
 * scan was the first design and it was wrong twice over: it would have found definitions a service
 * declared for a different deployment and never registers here, and it would have made "which
 * settings does this service have" a question answerable only at runtime. A source is a list
 * somebody wrote down, which is also the list a reviewer reads.
 *
 * <p>Several sources may be registered - one per bounded context is a reasonable layout - and their
 * definitions are merged. Two sources declaring the same key with different shapes is a startup
 * failure; declaring it identically is not, so a shared definition may appear in more than one list
 * without either owner having to know about the other.
 */
@FunctionalInterface
public interface SettingDefinitionSource {

    Collection<SettingDefinition<?>> definitions();

    static SettingDefinitionSource of(SettingDefinition<?>... definitions) {
        List<SettingDefinition<?>> declared = List.copyOf(Arrays.asList(definitions));
        return () -> declared;
    }

    static SettingDefinitionSource of(Collection<SettingDefinition<?>> definitions) {
        List<SettingDefinition<?>> declared = List.copyOf(definitions);
        return () -> declared;
    }
}
