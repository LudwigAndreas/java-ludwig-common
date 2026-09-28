package ru.ludwigandreas.storage.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything this module can be told, and nothing it should not be.
 *
 * <p>There is no {@code access-key} and no {@code secret-key} property, and their absence is the
 * point rather than an omission: see {@link Credentials} for how a credential reaches the client and
 * why none of the ways it can travels through configuration this class would hold.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ludwig.storage")
public class ObjectStorageProperties {

    /**
     * Whether the module wires anything at all.
     *
     * <p>Default on, because a service that put this starter on its classpath meant to. Turning it
     * off leaves the interfaces available for a service that supplies its own {@code ObjectStore}.
     */
    private boolean enabled = true;

    /**
     * Which implementation the primary {@code ObjectStore} bean is.
     *
     * <p>{@code s3} or {@code filesystem}. Defaults to {@code filesystem}, and that default is
     * deliberately the one that cannot reach anything outside the machine: a service that has not
     * been told where its bucket is should fail to find a local directory, not start talking to
     * whatever bucket the ambient credentials happen to reach.
     */
    private StoreType type = StoreType.FILESYSTEM;

    /** The S3 half of the configuration; ignored when {@code type} is {@code filesystem}. */
    private final S3 s3 = new S3();

    /** The filesystem half; ignored when {@code type} is {@code s3}. */
    private final Filesystem filesystem = new Filesystem();

    /** Which implementation to wire. */
    public enum StoreType {

        /** Object storage speaking the S3 API. */
        S3,

        /** A directory on this machine. */
        FILESYSTEM
    }

    /** How the S3 client is built. */
    @Getter
    @Setter
    public static class S3 {

        /**
         * The region to sign for.
         *
         * <p>Required even against a non-AWS store, which will ignore it: SigV4 signs the region into
         * the request, so the SDK cannot construct one without a value. {@code us-east-1} is the
         * conventional answer for a MinIO or Ceph endpoint that does not care.
         */
        private String region = "us-east-1";

        /**
         * The endpoint to talk to, when it is not AWS.
         *
         * <p>Left unset for AWS itself, where the SDK derives the endpoint from the region and the
         * bucket. Set to {@code http://localhost:9000} or similar for MinIO, LocalStack or Ceph.
         */
        private String endpoint;

        /**
         * Whether to address buckets as a path segment rather than a subdomain.
         *
         * <p>Off by default, matching AWS. It has to be on for almost every self-hosted store: those
         * are reached by an IP address or a bare host name, and {@code bucket.10.0.0.5} does not
         * resolve. Getting this wrong produces a DNS failure rather than an S3 error, which is why it
         * is called out here instead of being left for somebody to find.
         */
        private boolean pathStyleAccess;

        /** How the client proves who it is. */
        private final Credentials credentials = new Credentials();

        /**
         * Attempts per request, inclusive of the first.
         *
         * <p>Two, meaning one retry, and see {@code S3ObjectStore}'s class documentation for why this
         * is small but not zero: the SDK's retry layer is also what follows a bucket's region
         * redirect and re-signs a clock-skewed request, and zero attempts would turn both of those
         * into failures no caller-side retry could fix. Anything larger gives the caller a second
         * backoff budget underneath its own, which is exactly what {@code ObjectStore} says
         * implementations must not do.
         */
        @Min(1)
        private int maxAttempts = 2;

        /** How long to wait for a connection to be established. */
        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(5);

        /**
         * How long a socket may be idle mid-response before the read is abandoned.
         *
         * <p>Generous, because this is the timeout a ranged read of a large object lives under and it
         * measures the gap between bytes rather than the length of the transfer. A value tight enough
         * to be a useful liveness check on a small request will kill a healthy multi-gigabyte read on
         * a slow link.
         */
        @NotNull
        private Duration socketTimeout = Duration.ofSeconds(60);

        /**
         * How long to wait for a connection from the pool before giving up.
         *
         * <p>Its own timeout because pool exhaustion and a slow server are different faults with the
         * same symptom. A job leaking response streams shows up here, quickly and by name, instead of
         * as a hang.
         */
        @NotNull
        private Duration connectionAcquireTimeout = Duration.ofSeconds(10);

        /** Connections the pool may hold open. */
        @Min(1)
        private int maxConnections = 50;

        /**
         * Keys per listing request, or zero to let the store decide (S3's own default is 1000).
         *
         * <p>Rarely worth setting in a deployment. It exists because a listing is lazy, so the page
         * size is also the amount of a listing held in memory at once, and because this module's own
         * pagination test needs a page smaller than the number of objects it writes - S3's default of
         * 1000 would make that test either slow or vacuous.
         */
        @Min(0)
        private int listPageSize;
    }

    /**
     * How a credential reaches the client.
     *
     * <h2>Why there is nothing to configure here by default</h2>
     *
     * <p>The default resolves through the SDK's {@code DefaultCredentialsProvider} chain: environment
     * variables, the web identity token a Kubernetes service account projects (IRSA and its
     * equivalents), the shared profile file, and finally the instance metadata service. Every one of
     * those puts the secret somewhere the deployment already manages it, and none of them puts it in
     * a file this repository would hold - which is the same rule jib and {@code deploy} follow for
     * the registry and the artifact repository, for the same reason.
     *
     * <p>{@code static} exists for one case: a developer running MinIO or LocalStack locally, where
     * the credential is a well-known constant that is not a secret at all. It is off unless
     * explicitly switched on, and switching it on in a deployed profile is the mistake this split is
     * shaped to make visible in a diff.
     */
    @Getter
    @Setter
    public static class Credentials {

        /** Which provider to use: {@code default} for the SDK chain, {@code static} for the pair below. */
        private CredentialsSource source = CredentialsSource.DEFAULT;

        /** The access key, for {@code static} only. Never set this in a deployed profile. */
        private String accessKey;

        /** The secret key, for {@code static} only. Never set this in a deployed profile. */
        private String secretKey;
    }

    /** Where a credential comes from. */
    public enum CredentialsSource {

        /** The SDK's own chain: environment, web identity token, profile, instance metadata. */
        DEFAULT,

        /** The literal pair in configuration; for a local MinIO or LocalStack and nothing else. */
        STATIC
    }

    /** How the filesystem store is built. */
    @Getter
    @Setter
    public static class Filesystem {

        /**
         * The directory objects live under.
         *
         * <p>Unset by default, in which case the module uses a directory under the JVM's temp
         * directory and creates it. A configured directory is never created: a typo in a path that
         * silently created a directory would put data somewhere nobody is watching, and the two cases
         * are indistinguishable afterwards.
         */
        private String root;
    }
}
