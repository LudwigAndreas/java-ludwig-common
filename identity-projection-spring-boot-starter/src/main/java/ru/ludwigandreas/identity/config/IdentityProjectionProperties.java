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

    private final Kafka kafka = new Kafka();
    private final Liquibase liquibase = new Liquibase();

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

    public Kafka getKafka() {
        return kafka;
    }

    public Liquibase getLiquibase() {
        return liquibase;
    }

    public static class Kafka {

        private boolean enabled = true;

        /** The provider's user topic. Also referenced directly by the listener's annotation. */
        private String topic = "oidc.users";

        /**
         * Consumer group. Every service keeps its own projection and therefore needs its own group -
         * sharing one would mean each service sees only a share of the events.
         */
        private String groupId;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
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
