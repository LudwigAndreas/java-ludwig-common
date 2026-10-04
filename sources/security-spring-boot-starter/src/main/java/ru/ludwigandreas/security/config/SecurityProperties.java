package ru.ludwigandreas.security.config;

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
    private final Pat pat = new Pat();

    public Pat getPat() {
        return pat;
    }

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

        public boolean isRequireResolver() {
            return requireResolver;
        }

        public void setRequireResolver(boolean requireResolver) {
            this.requireResolver = requireResolver;
        }
    }

    /*
        ludwig.security.authorities.cache is gone. The authority cache's TTL, size and off switch moved to
        ludwig.cache.caches.authorities, owned by cache-spring-boot-starter, because this module's cache and
        user-settings' cache were the same three classes written twice and a second configuration surface for
        the same behaviour would have outlived the duplication it described. The TTL's MEANING stayed here,
        in AuthorityCaches, which is the part that was never generic: purpose=security is what gets that
        number a startup ceiling and what refuses the stale-read affordances.

            ludwig:
              cache:
                caches:
                  authorities:
                    ttl: 30s            # was ludwig.security.authorities.cache.ttl
                    maximum-size: 10000 # was ludwig.security.authorities.cache.maximum-size
                    enabled: true       # was ludwig.security.authorities.cache.enabled
    */

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

    /**
     * How long a revoked personal access token can still work, declared so it can be refused.
     *
     * <p>Every value here is a term in one of two sums, and the sums are the point. Nobody multiplies out
     * three independently configured TTLs in production, so the module does it at startup, logs both totals
     * and refuses to start above the ceiling - the same mechanism and the same reasoning as the existing
     * audience check, whose javadoc says a defect that cannot be discovered by testing is worth refusing to
     * start over.
     *
     * <pre>
     *   request/response callers:  assertion lifetime + edge cache lifetime + authority cache TTL
     *   long-lived connections:    revalidation interval + authority cache TTL
     * </pre>
     *
     * <p><b>Both are computed.</b> Computing only the first would be worse than computing neither: it would
     * log a correct-looking number while being false for exactly the callers whose window is largest.
     */
    public static class Pat {

        /**
         * How long an exchanged assertion is valid. Set to match what the issuer actually mints - this
         * module cannot read the issuer's configuration, so a wrong value here makes the computed window
         * wrong in the dangerous direction.
         */
        private java.time.Duration assertionLifetime = java.time.Duration.ofMinutes(5);

        /**
         * How long the edge may reuse a cached assertion for one token and audience.
         *
         * <p>Declared here although the edge is not in this repository and no build can check it, because
         * leaving it out of the sum would mean the computed window silently excludes the largest term for a
         * correctly configured edge. See the module README for the edge's obligations.
         */
        private java.time.Duration edgeCacheLifetime = java.time.Duration.ofMinutes(5);

        /**
         * How often a service holding a long-lived connection open must re-derive the caller's authority.
         *
         * <p>Ships before any transport that needs it, so the knob exists before the first streaming
         * transport does. There is deliberately no ArchUnit rule for the requirement: nothing in this
         * repository holds a connection open, so a rule would pass vacuously and read as coverage. The
         * enforcing test is a recorded obligation on the change that adds the first transport.
         */
        private java.time.Duration revalidationInterval = java.time.Duration.ofMinutes(1);

        /** The ceiling. Startup fails when either composition exceeds it. */
        private java.time.Duration maxRevocationWindow = java.time.Duration.ofMinutes(15);

        /** Whether to compute, log and enforce the window at all. Off only for a test that wants it off. */
        private boolean validateRevocationWindow = true;

        public java.time.Duration getAssertionLifetime() {
            return assertionLifetime;
        }

        public void setAssertionLifetime(java.time.Duration assertionLifetime) {
            this.assertionLifetime = assertionLifetime;
        }

        public java.time.Duration getEdgeCacheLifetime() {
            return edgeCacheLifetime;
        }

        public void setEdgeCacheLifetime(java.time.Duration edgeCacheLifetime) {
            this.edgeCacheLifetime = edgeCacheLifetime;
        }

        public java.time.Duration getRevalidationInterval() {
            return revalidationInterval;
        }

        public void setRevalidationInterval(java.time.Duration revalidationInterval) {
            this.revalidationInterval = revalidationInterval;
        }

        public java.time.Duration getMaxRevocationWindow() {
            return maxRevocationWindow;
        }

        public void setMaxRevocationWindow(java.time.Duration maxRevocationWindow) {
            this.maxRevocationWindow = maxRevocationWindow;
        }

        public boolean isValidateRevocationWindow() {
            return validateRevocationWindow;
        }

        public void setValidateRevocationWindow(boolean validateRevocationWindow) {
            this.validateRevocationWindow = validateRevocationWindow;
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
