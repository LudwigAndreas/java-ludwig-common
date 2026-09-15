package ru.ludwigandreas.security.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the module can be configured with, under {@code ludwig.security}.
 *
 * <p>The defaults are chosen to fail closed: data access defaults to {@code NONE}, unknown policy
 * tokens are a startup error, and the mTLS path refuses to start without an explicit trusted-proxy
 * list. A service that configures nothing gets a service nobody can read data from, which is the
 * failure mode you find in the first test rather than in an incident.
 */
@ConfigurationProperties(prefix = "ludwig.security")
public class SecurityProperties {

    /** Master switch for the whole module's autoconfiguration. */
    private boolean enabled = true;

    /** Prefix for the {@code type} URI of the RFC 7807 problems this module emits. */
    private String problemTypePrefix = "urn:ludwig:security:";

    /**
     * Paths served without authentication. Keep this list short and specific: it is the one place
     * where a wildcard genuinely does open a door.
     */
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/actuator/health", "/actuator/health/**", "/actuator/info"));

    /** Whether client-supplied identity headers are stripped - see {@code IdentityHeaderStrippingFilter}. */
    private boolean stripIdentityHeaders = true;

    private final Jwt jwt = new Jwt();
    private final Mtls mtls = new Mtls();
    private final AuthorityConfig authorities = new AuthorityConfig();
    private final Data data = new Data();
    private final Audit audit = new Audit();
    private final Metrics metrics = new Metrics();
    private final SystemPrincipal systemPrincipal = new SystemPrincipal();

    public enum DefaultAccess {
        /** Anything without a matching policy is denied. The only safe default. */
        NONE,
        /**
         * Anything without a matching policy is unrestricted. For migrating an existing service: turn
         * it on, watch {@code ludwig.security.data.scope} for resources reporting {@code ALL}, write
         * their policies, then turn it off. Not a resting state.
         */
        ALL
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProblemTypePrefix() {
        return problemTypePrefix;
    }

    public void setProblemTypePrefix(String problemTypePrefix) {
        this.problemTypePrefix = problemTypePrefix;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public boolean isStripIdentityHeaders() {
        return stripIdentityHeaders;
    }

    public void setStripIdentityHeaders(boolean stripIdentityHeaders) {
        this.stripIdentityHeaders = stripIdentityHeaders;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Mtls getMtls() {
        return mtls;
    }

    public AuthorityConfig getAuthorities() {
        return authorities;
    }

    public Data getData() {
        return data;
    }

    public Audit getAudit() {
        return audit;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public SystemPrincipal getSystemPrincipal() {
        return systemPrincipal;
    }

    /** Validation of the token the edge mints from the browser session, and of service tokens. */
    public static class Jwt {

        private boolean enabled = true;

        /** Claim holding the stable caller id. Almost always {@code sub}. */
        private String subjectClaim = "sub";

        /** Claim used for display only. Never for an authorization decision. */
        private String nameClaim = "name";

        /** Claim holding the tenant, in a multi-tenant deployment. */
        private String tenantClaim = "tenant_id";

        /**
         * Presence of this claim marks the token as belonging to a peer service rather than a person,
         * so it gets {@code PrincipalType.SERVICE} and its own grants. Blank disables the distinction.
         */
        private String serviceClientClaim = "client_id";

        /**
         * Audiences this service accepts. Empty means the check is skipped, which in a mesh where every
         * service trusts the same issuer lets a token minted for any service be replayed here - so
         * {@link #requireAudience} makes an empty list a startup failure instead.
         */
        private List<String> audiences = new ArrayList<>();

        private boolean requireAudience = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSubjectClaim() {
            return subjectClaim;
        }

        public void setSubjectClaim(String subjectClaim) {
            this.subjectClaim = subjectClaim;
        }

        public String getNameClaim() {
            return nameClaim;
        }

        public void setNameClaim(String nameClaim) {
            this.nameClaim = nameClaim;
        }

        public String getTenantClaim() {
            return tenantClaim;
        }

        public void setTenantClaim(String tenantClaim) {
            this.tenantClaim = tenantClaim;
        }

        public String getServiceClientClaim() {
            return serviceClientClaim;
        }

        public void setServiceClientClaim(String serviceClientClaim) {
            this.serviceClientClaim = serviceClientClaim;
        }

        public List<String> getAudiences() {
            return audiences;
        }

        public void setAudiences(List<String> audiences) {
            this.audiences = audiences;
        }

        public boolean isRequireAudience() {
            return requireAudience;
        }

        public void setRequireAudience(boolean requireAudience) {
            this.requireAudience = requireAudience;
        }
    }

    /** Partner and service-to-service authentication from client certificates. */
    public static class Mtls {

        private boolean enabled = false;

        /**
         * CIDRs of the peers allowed to assert {@code x-forwarded-client-cert} - the mesh sidecar or
         * ingress, nothing wider. Required when {@link #enabled}; see {@code TrustedProxies}.
         */
        private List<String> trustedProxies = new ArrayList<>();

        /**
         * SPIFFE prefix identifying certificates issued to our own workloads, so a peer service is
         * recognized as {@code SERVICE} without being enumerated as a partner.
         */
        private String serviceIdentityPrefix;

        /** Partner id -> how to recognize its certificate. */
        private Map<String, Partner> partners = new LinkedHashMap<>();

        /**
         * Acknowledge that {@code server.forward-headers-strategy=native} is safe in this deployment.
         *
         * <p>Tomcat's {@code RemoteIpValve} rewrites the request's remote address from
         * {@code X-Forwarded-For} before any filter runs, and by default it trusts that header from any
         * private-range peer. In a cluster that means a pod on 10.0.0.0/8 can set the header to the
         * sidecar's address, pass the trusted-proxy check and have a forged
         * {@code x-forwarded-client-cert} believed. The module refuses to start in that combination.
         *
         * <p>Set this to true only if the valve's {@code internal-proxies} is narrowed to exactly the
         * proxies you trust - at which point the rewrite is sound and the refusal is over-cautious.
         */
        private boolean trustNativeForwardHeaders = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getTrustedProxies() {
            return trustedProxies;
        }

        public void setTrustedProxies(List<String> trustedProxies) {
            this.trustedProxies = trustedProxies;
        }

        public String getServiceIdentityPrefix() {
            return serviceIdentityPrefix;
        }

        public void setServiceIdentityPrefix(String serviceIdentityPrefix) {
            this.serviceIdentityPrefix = serviceIdentityPrefix;
        }

        public Map<String, Partner> getPartners() {
            return partners;
        }

        public void setPartners(Map<String, Partner> partners) {
            this.partners = partners;
        }

        public boolean isTrustNativeForwardHeaders() {
            return trustNativeForwardHeaders;
        }

        public void setTrustNativeForwardHeaders(boolean trustNativeForwardHeaders) {
            this.trustNativeForwardHeaders = trustNativeForwardHeaders;
        }
    }

    /**
     * One partner's certificate identifiers. Set exactly one of {@code spiffeId}, {@code dnsSan} or
     * {@code subjectDn}; {@code certificateHash} optionally pins the match to one certificate.
     */
    public static class Partner {

        private String displayName;
        private String spiffeId;
        private String dnsSan;
        private String subjectDn;
        private String certificateHash;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getSpiffeId() {
            return spiffeId;
        }

        public void setSpiffeId(String spiffeId) {
            this.spiffeId = spiffeId;
        }

        public String getDnsSan() {
            return dnsSan;
        }

        public void setDnsSan(String dnsSan) {
            this.dnsSan = dnsSan;
        }

        public String getSubjectDn() {
            return subjectDn;
        }

        public void setSubjectDn(String subjectDn) {
            this.subjectDn = subjectDn;
        }

        public String getCertificateHash() {
            return certificateHash;
        }

        public void setCertificateHash(String certificateHash) {
            this.certificateHash = certificateHash;
        }
    }

    /** Role resolution and its cache. */
    public static class AuthorityConfig {

        /**
         * Fail startup unless a real {@code AuthorityResolver} is registered. Turn this on in every
         * deployed environment: it is what stops a service from silently running on the fallback
         * resolver, where nobody has any role and every authorization rule quietly denies - or, on an
         * endpoint whose rule is written as a denial, quietly permits.
         */
        private boolean requireResolver = false;

        private final Cache cache = new Cache();

        public boolean isRequireResolver() {
            return requireResolver;
        }

        public void setRequireResolver(boolean requireResolver) {
            this.requireResolver = requireResolver;
        }

        public Cache getCache() {
            return cache;
        }
    }

    public static class Cache {

        private boolean enabled = true;

        /**
         * How long a revoked role can still work. Seconds, not minutes - and the identity projection
         * evicts on change anyway, so this is the backstop for a missed event, not the normal path.
         */
        private Duration ttl = Duration.ofSeconds(60);

        private long maximumSize = 10_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public long getMaximumSize() {
            return maximumSize;
        }

        public void setMaximumSize(long maximumSize) {
            this.maximumSize = maximumSize;
        }
    }

    /** Row-level authorization. */
    public static class Data {

        private boolean enabled = true;

        /** What applies to a resource/action pair with no policy. */
        private DefaultAccess defaultAccess = DefaultAccess.NONE;

        /** Reject policy tokens other than ALL/NONE/OWN/TENANT/PARTNER. Turn off to use custom axes. */
        private boolean strictPolicyTokens = true;

        /**
         * Resources deliberately exempt from scoping - reference data, public catalogs. Being on this
         * list is the only way an unmapped resource is allowed through, so adding an entry is a
         * reviewable decision rather than an omission.
         */
        private List<String> unscopedResources = new ArrayList<>();

        /** resource -> action -> role -> grant ({@code ALL}, {@code NONE}, {@code OWN+TENANT}, ...). */
        private Map<String, Map<String, Map<String, String>>> policies = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public DefaultAccess getDefaultAccess() {
            return defaultAccess;
        }

        public void setDefaultAccess(DefaultAccess defaultAccess) {
            this.defaultAccess = defaultAccess;
        }

        public boolean isStrictPolicyTokens() {
            return strictPolicyTokens;
        }

        public void setStrictPolicyTokens(boolean strictPolicyTokens) {
            this.strictPolicyTokens = strictPolicyTokens;
        }

        public List<String> getUnscopedResources() {
            return unscopedResources;
        }

        public void setUnscopedResources(List<String> unscopedResources) {
            this.unscopedResources = unscopedResources;
        }

        public Map<String, Map<String, Map<String, String>>> getPolicies() {
            return policies;
        }

        public void setPolicies(Map<String, Map<String, Map<String, String>>> policies) {
            this.policies = policies;
        }
    }

    public static class Audit {

        private boolean enabled = true;

        /** Also record granted decisions, not only denials. Volume grows with traffic. */
        private boolean logGrants = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isLogGrants() {
            return logGrants;
        }

        public void setLogGrants(boolean logGrants) {
            this.logGrants = logGrants;
        }
    }

    public static class Metrics {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Identity for work that has no caller - see {@code SystemPrincipalTemplate}. */
    public static class SystemPrincipal {

        private String subject = "system";

        private List<String> roles = new ArrayList<>();

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }
}
