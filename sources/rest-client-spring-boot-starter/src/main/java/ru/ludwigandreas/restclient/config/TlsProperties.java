package ru.ludwigandreas.restclient.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * TLS for one named client: what it trusts, what it presents, and what it negotiates.
 *
 * <p>Left entirely unset, the client uses the JVM's default trust material, which is the right
 * answer for a public endpoint and the wrong one for an internal PKI - hence
 * {@link #getTruststorePath()}.
 */
@Getter
@Setter
public class TlsProperties {

    /**
     * Truststore holding the CAs this client will accept. Absent means the JVM default.
     *
     * <p>Accepts any Spring resource location, so {@code classpath:}, {@code file:} and a bare path
     * all work; a Kubernetes secret mounted as a file is the usual production form.
     */
    private String truststorePath;

    /** Password for {@link #getTruststorePath()}. Resolve it from a secret, never a literal. */
    private String truststorePassword;

    /** Truststore format. Built-in default: PKCS12. */
    private String truststoreType;

    /** Keystore holding this client's own certificate and key - i.e. mTLS. */
    private String keystorePath;

    /** Password for {@link #getKeystorePath()}. */
    private String keystorePassword;

    /** Password of the private key inside the keystore, when it differs from the store password. */
    private String keyPassword;

    /** Keystore format. Built-in default: PKCS12. */
    private String keystoreType;

    /**
     * Enabled protocols. Built-in default: TLSv1.3 and TLSv1.2.
     *
     * <p>Stated rather than inherited from the JVM so that a service running on a JDK whose defaults
     * still admit TLSv1.1 does not quietly negotiate it with a partner that also still offers it.
     */
    private List<String> protocols;

    /** Enabled cipher suites. Absent means the JVM's default selection for the protocols above. */
    private List<String> cipherSuites;

    /**
     * Whether the certificate's names are checked against the host being called. Built-in
     * default: true.
     *
     * <p>Turning this off is a smaller hole than {@link #getTrustAll()} and a hole nonetheless: it
     * accepts a valid certificate issued for a different name, which is exactly what an attacker
     * who can obtain any certificate from the trusted CA needs. It is guarded by the same
     * {@code allow-trust-all} switch for that reason.
     */
    private Boolean hostnameVerification;

    /**
     * Accept any server certificate. Never in production.
     *
     * <p>Setting this to {@code true} is refused at startup unless {@code ludwig.rest-client
     * .allow-trust-all} is also {@code true} AND no profile from
     * {@code ludwig.rest-client.production-profiles} is active. Two independent switches, because
     * one switch is a switch somebody flips in a hurry during an incident and never flips back - and
     * a client that silently stopped verifying certificates six months ago is indistinguishable, from
     * the outside, from one that never did.
     */
    private Boolean trustAll;
}
