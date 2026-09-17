package ru.ludwigandreas.notification.service.queue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Who this process is, as recorded in a delivery lease and in a distributed lock.
 *
 * <p>The hostname alone is not enough: two replicas can share a host, and in a test two contexts
 * certainly do. Two instances with the same owner string would each believe the other's leases were
 * their own, so the shutdown drain would release deliveries another instance is actively sending.
 * The random suffix makes that unrepresentable.
 *
 * <p>Configurable, because in a StatefulSet the pod name is a better answer than the hostname plus
 * noise - it survives a restart, so an operator can correlate a stranded lease with a pod that is
 * still there.
 */
public final class InstanceIdentity {

    /** Random characters appended to the hostname; short enough to stay readable in a column. */
    private static final int SUFFIX_LENGTH = 8;

    private static final String UNKNOWN_HOST = "unknown-host";

    private InstanceIdentity() {
    }

    public static String resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = UNKNOWN_HOST;
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, SUFFIX_LENGTH);
    }
}
