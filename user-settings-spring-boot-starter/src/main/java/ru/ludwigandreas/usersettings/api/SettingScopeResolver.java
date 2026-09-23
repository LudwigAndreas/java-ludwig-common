package ru.ludwigandreas.usersettings.api;

import java.util.List;
import org.springframework.core.Ordered;

/**
 * Produces the scopes of one layer that apply to a subject - the pluggable half of resolution.
 *
 * <p>The engine knows the layer order; it does not know what a layer <em>means</em> in a given
 * platform. "The user's roles" is a directory lookup in one deployment and a group membership in
 * another; "the tenant" may be an organization, a legal entity or a franchise. Each of those is a
 * resolver, and replacing one does not touch precedence, caching, validation or auditing.
 *
 * <p><b>Ordering within a layer is a policy decision.</b> A user in three roles that each set the
 * same key gets the value from whichever scope this resolver listed first. That is not a tie the
 * engine can break sensibly on its own - "the most permissive" and "the most recently granted" are
 * both defensible and neither is universal - so it is handed to the resolver, which is where the
 * platform's own rule lives. Returning roles in an unstable order (a plain {@code HashSet}) makes
 * the resolved value non-deterministic; the shipped resolver preserves the order the authority
 * lookup returned.
 */
public interface SettingScopeResolver extends Ordered {

    /** The layer this resolver supplies scopes for. */
    SettingLayer layer();

    /**
     * The scopes of {@link #layer()} that apply to this subject.
     *
     * @return the scopes that apply, most significant first; empty when the layer does not apply to
     *         this subject at all
     */
    List<SettingScope> scopesFor(SettingsSubject subject);

    @Override
    default int getOrder() {
        return 0;
    }
}
