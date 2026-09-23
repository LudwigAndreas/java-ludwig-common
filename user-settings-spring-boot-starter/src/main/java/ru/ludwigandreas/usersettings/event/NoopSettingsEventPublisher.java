package ru.ludwigandreas.usersettings.event;

/**
 * Publishes nothing. Registered when the outbox module is absent or
 * {@code ludwig.user-settings.owner.publish-events=false}.
 *
 * <p>The right default for a service that owns its users' settings and is the only thing that reads
 * them - an account service with no notification service behind it yet. Turning publication on later
 * is a property and a dependency, not a code change.
 */
public class NoopSettingsEventPublisher implements SettingsEventPublisher {

    @Override
    public void publishSettingChanged(UserSettingChangedEvent event) {
        // nothing to publish
    }

    @Override
    public void publishConsentChanged(ConsentChangedEvent event) {
        // nothing to publish
    }

    @Override
    public void republishConsent(ConsentChangedEvent event) {
        // nothing to publish
    }

    @Override
    public boolean publishes() {
        return false;
    }
}
