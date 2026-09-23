package ru.ludwigandreas.usersettings.resolve;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import ru.ludwigandreas.usersettings.api.ScopedValue;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingValueSource;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.entity.UserSettingValueEntity;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;

/**
 * Stored values, fetched for every scope in one query.
 *
 * <p>Ordered ahead of the configured source so that a stored row beats a configured default at the
 * same scope. That is the behaviour an operator expects: setting a tenant default in configuration
 * establishes a starting point, and an administrator overriding it through the API has to win -
 * otherwise the override appears to save and then silently does nothing.
 */
@RequiredArgsConstructor
public class PersistentSettingValueSource implements SettingValueSource {

    /** Ahead of the configured source, which sits at the default order. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    private final UserSettingValueRepository repository;

    @Override
    public List<ScopedValue> load(SettingsSubject subject, List<SettingScope> scopes) {
        return repository.loadForScopes(subject.tenantId(), scopes).stream()
                .map(PersistentSettingValueSource::toScopedValue)
                .toList();
    }

    private static ScopedValue toScopedValue(UserSettingValueEntity entity) {
        return new ScopedValue(
                new SettingScope(entity.getScopeType(), entity.getScopeId()),
                entity.getSettingKey(),
                entity.getValueText(),
                entity.getValueType(),
                entity.getChangedAt());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
