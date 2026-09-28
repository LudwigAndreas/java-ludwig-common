package ru.ludwigandreas.usersettings.event;

/**
 * Publishes owner-mode changes so projections can follow them.
 *
 * <p>An interface with a no-op implementation rather than an optional dependency threaded through
 * the writer: a service can own settings without anybody projecting them, and that deployment should
 * not have to run an outbox. The writer calls this on every change either way and never branches on
 * whether publication is configured.
 *
 * <p>Implementations are called from inside the writer's transaction, which is what makes the change
 * and its event atomic: a committed change is always published and a rolled-back one never is.
 */
public interface SettingsEventPublisher {

    void publishSettingChanged(UserSettingChangedEvent event);

    void publishConsentChanged(ConsentChangedEvent event);

    /**
     * Republishes a decision that was already published once, for the backfill.
     *
     * <p>A separate method rather than a flag, because the difference is not cosmetic. The normal
     * path stamps the consent id onto the event as an outbox idempotency key, so an at-least-once
     * handler that publishes the same decision twice queues it once. That is exactly wrong for a
     * backfill: the key from the original publication may still be in the outbox, in which case the
     * republish would be recognized as a duplicate, swallowed, and the operator would watch a
     * backfill report thousands of rows while the projection received nothing.
     *
     * <p>Dropping the key is safe because the projection does not rely on it. A consent row is keyed
     * on the owner's id and is never updated, so a duplicate delivery finds the row already there
     * and returns - the de-duplication lives at the consumer, where it survives the outbox forgetting
     * anything.
     */
    void republishConsent(ConsentChangedEvent event);

    /**
     * Whether this publisher actually sends anything.
     *
     * <p>Only the backfill asks. Every other caller publishes unconditionally and is right not to
     * care - a service that owns settings nobody projects should not have to branch. A backfill whose
     * events go nowhere, though, is not a no-op but a trap: it would report success, take minutes,
     * and leave the replica exactly as empty as it found it.
     */
    default boolean publishes() {
        return true;
    }
}
