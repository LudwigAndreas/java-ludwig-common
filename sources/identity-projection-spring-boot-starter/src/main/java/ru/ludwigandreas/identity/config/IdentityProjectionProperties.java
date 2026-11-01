package ru.ludwigandreas.identity.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the identity projection, under {@code ludwig.identity}.
 */
@ConfigurationProperties(prefix = "ludwig.identity")
public class IdentityProjectionProperties {

    /** Master switch for the whole module's autoconfiguration. */
    private boolean enabled = true;

    /**
     * Workload id (usually a SPIFFE id) -> roles, for peer services calling this one.
     *
     * <p>In configuration rather than in a table because which service may call which is part of the
     * deployment topology: it changes when code changes, and it should be reviewed in the same pull
     * request as the call it authorizes.
     */
    private Map<String, Set<String>> serviceRoles = new LinkedHashMap<>();

    /**
     * Register the grant-table {@code DataScopeProvider}. Turn it off in a service whose data scopes come
     * entirely from role policy - the grant table then costs a query per scoped request for nothing.
     */
    private boolean grantsEnabled = true;

    /**
     * Register the partner-registry {@code PartnerIdentityResolver}, replacing the security module's
     * configuration-driven one. Only relevant when {@code ludwig.security.mtls.enabled=true}.
     */
    private boolean partnerRegistryEnabled = true;

    /**
     * The identity sources this service accepts, by name. Every feed names one of them; a feed naming another fails
     * startup. A name is lower-case letters, digits and hyphens, starting with a letter.
     */
    private Map<String, Source> sources = new LinkedHashMap<>();

    private final Sweep sweep = new Sweep();
    private final Liquibase liquibase = new Liquibase();
    private final Contact contact = new Contact();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Set<String>> getServiceRoles() {
        return serviceRoles;
    }

    public void setServiceRoles(Map<String, Set<String>> serviceRoles) {
        this.serviceRoles = serviceRoles;
    }

    public boolean isGrantsEnabled() {
        return grantsEnabled;
    }

    public void setGrantsEnabled(boolean grantsEnabled) {
        this.grantsEnabled = grantsEnabled;
    }

    public boolean isPartnerRegistryEnabled() {
        return partnerRegistryEnabled;
    }

    public void setPartnerRegistryEnabled(boolean partnerRegistryEnabled) {
        this.partnerRegistryEnabled = partnerRegistryEnabled;
    }

    public Map<String, Source> getSources() {
        return sources;
    }

    public void setSources(Map<String, Source> sources) {
        this.sources = sources;
    }

    public Sweep getSweep() {
        return sweep;
    }

    public Contact getContact() {
        return contact;
    }

    public Liquibase getLiquibase() {
        return liquibase;
    }

    /**
     * Whether the projection stores the user's verified contact data.
     *
     * <p>Off by default, and that is the whole point of it being a switch. The projection is
     * replicated into every service that uses this module, so a contact column that was always
     * populated would put a person's address in every service database in the estate - including the
     * ones with no reason to have it, and every one of them a place a deletion request has to reach.
     *
     * <p>A service that actually sends messages to people - the notification service - turns it on and
     * reads the address from the directory rather than keeping its own copy that nothing keeps in step.
     */
    public static class Contact {

        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** One identity source: a provider, and how its role vocabulary becomes the platform's. */
    public static class Source {

        /**
         * Provider role or group -> the platform roles it stands for. A mapped role is replaced by its targets. Role
         * names are compared without the {@code ROLE_} prefix, and case-sensitively, as the provider spells them.
         */
        private Map<String, Set<String>> roleMapping = new LinkedHashMap<>();

        /** What becomes of a provider role the mapping does not name. */
        private UnmappedRoles unmappedRoles = UnmappedRoles.KEEP;

        public Map<String, Set<String>> getRoleMapping() {
            return roleMapping;
        }

        public void setRoleMapping(Map<String, Set<String>> roleMapping) {
            this.roleMapping = roleMapping;
        }

        public UnmappedRoles getUnmappedRoles() {
            return unmappedRoles;
        }

        public void setUnmappedRoles(UnmappedRoles unmappedRoles) {
            this.unmappedRoles = unmappedRoles;
        }
    }

    /** What becomes of a provider role a source's mapping does not name. */
    public enum UnmappedRoles {
        /** Kept under the provider's name: right when the provider already speaks the platform's role names. */
        KEEP,
        /** Dropped: right when the provider's groups are broader than this service's roles. */
        DROP
    }

    /** Complete listings of a source. */
    public static class Sweep {

        /**
         * The largest share of a source's active users one sweep may disable. A sweep that would disable more is
         * aborted with no change, because an empty or truncated listing must not lock out an organisation.
         */
        private double maxDisableRatio = 0.1;

        public double getMaxDisableRatio() {
            return maxDisableRatio;
        }

        public void setMaxDisableRatio(double maxDisableRatio) {
            this.maxDisableRatio = maxDisableRatio;
        }
    }

    public static class Liquibase {

        /**
         * Apply the shipped changelog as an independent {@code SpringLiquibase}. Turn this off and include
         * {@code classpath:db/changelog/identity/identity-changelog.xml} from the application's own master
         * changelog when migration order across modules matters.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
