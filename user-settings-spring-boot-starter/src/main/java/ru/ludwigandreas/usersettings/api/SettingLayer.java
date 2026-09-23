package ru.ludwigandreas.usersettings.api;

/**
 * Where a resolved setting value came from, most specific first.
 *
 * <p>Declaration order <em>is</em> the precedence order - {@link #ordinal()} is compared directly
 * during resolution - so inserting a constant in the middle of this enum changes which value a user
 * sees. That is deliberate: a new layer is a behavioural change to every deployment, and making it
 * a one-line edit in the wrong place is how it would ship unnoticed.
 *
 * <p>Every resolved value reports its layer, and that is the point of the type existing at all. A
 * bare value answers "what is this user's timezone"; it cannot answer "why", which is the question
 * both a settings UI ("inherited from your organization") and an operator debugging a support ticket
 * ("I set this at the tenant level and it did not take effect") actually ask.
 */
public enum SettingLayer {

    /** Set by or for this specific user. Beats everything. */
    USER,

    /**
     * Set for a role or group the user holds. A user in several roles takes the value from the
     * first role the {@code SettingScopeResolver} listed, so that resolver's ordering is a policy
     * decision and not an accident of iteration order - see its Javadoc.
     */
    ROLE,

    /** Set for the user's tenant. */
    TENANT,

    /**
     * Set for the whole deployment, outside any tenant. Supplied by configuration rather than by a
     * row, which is what lets it be hot-reloaded without a redeploy.
     */
    PLATFORM,

    /** Nothing was set anywhere; the value is the one the {@link SettingDefinition} declares. */
    DEFAULT;

    /** Whether this layer takes precedence over {@code other}. */
    public boolean isMoreSpecificThan(SettingLayer other) {
        return ordinal() < other.ordinal();
    }
}
