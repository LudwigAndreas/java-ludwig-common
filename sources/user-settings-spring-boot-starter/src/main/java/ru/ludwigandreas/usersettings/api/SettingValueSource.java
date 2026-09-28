package ru.ludwigandreas.usersettings.api;

import java.util.List;
import org.springframework.core.Ordered;

/**
 * Where stored values come from, asked for every scope at once.
 *
 * <p>The signature is the whole point: one call carrying every scope, not one call per scope and
 * certainly not one per setting. The database source turns it into a single query, which is what
 * makes {@code getAll} a single round trip for a whole settings page or a whole notification
 * fan-out. An N+1 here would be this module's defining performance bug, and the way to make it
 * unrepresentable is to give the source no interface through which it could happen.
 *
 * <p>Sources are consulted in {@link Ordered} order and their results merged; where two sources
 * supply the same key in the same scope, the earlier source wins. That is how a stored tenant row
 * takes precedence over a configured tenant default without either source knowing about the other.
 */
public interface SettingValueSource extends Ordered {

    /**
     * Every value this source holds for any of these scopes, in one call.
     *
     * @param scopes every scope in play for this subject, across all layers
     * @return the values this source holds for any of those scopes, in any order
     */
    List<ScopedValue> load(SettingsSubject subject, List<SettingScope> scopes);

    @Override
    default int getOrder() {
        return 0;
    }
}
