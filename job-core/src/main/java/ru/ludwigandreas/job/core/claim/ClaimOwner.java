package ru.ludwigandreas.job.core.claim;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * The string this process writes into a {@code locked_by} / {@code owner_instance} column so that an
 * operator reading a stuck row can tell which pod is holding it.
 *
 * <h2>Why the hostname alone is not enough</h2>
 *
 * <p>Two replicas of the same deployment on one node, and any local run with two instances, share a
 * hostname. A lock owner that cannot distinguish them makes "is this row held by me or by the other
 * one?" unanswerable, which matters the moment a component wants to reclaim only <em>its own</em>
 * abandoned claims after a restart. The random suffix makes the identity per-process; it is short
 * because the value is read by humans in a database column far more often than it is parsed.
 *
 * <p>The identity is <em>not</em> stable across restarts, and must not be relied on as if it were: a
 * restarted pod is a new owner, and adopting work left behind by the previous one is an explicit
 * reclaim step, never an accident of the two sharing a name.
 */
public final class ClaimOwner {

    /**
     * Characters of a random UUID appended to the hostname. Enough that a collision between two
     * concurrently live instances is not a practical concern, short enough that the whole owner
     * string still fits comfortably in a log line and a narrow terminal column.
     */
    private static final int SUFFIX_LENGTH = 8;

    private static final String UNKNOWN_HOST = "unknown-host";

    private ClaimOwner() {
    }

    /**
     * Resolves the owner identity for this process.
     *
     * @param configured an explicitly configured owner, which wins when set - a deployment that
     *                   already has a meaningful instance identity (a StatefulSet ordinal, a pod
     *                   name) should use it rather than a synthesized one
     * @return {@code configured} when it carries text, otherwise {@code <hostname>-<random>}
     */
    public static String resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return hostname() + "-" + UUID.randomUUID().toString().substring(0, SUFFIX_LENGTH);
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // A container with no resolvable hostname is normal, and is not a reason to fail to
            // start: the random suffix still makes the owner unique, it is only less readable.
            return UNKNOWN_HOST;
        }
    }
}
