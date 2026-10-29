package ru.ludwigandreas.job.core.control;

import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.security.admin.AdminOperation;

/**
 * An administrative control that acts on state each replica holds in its own memory - a local cache, say - and is
 * therefore broadcast to every live replica rather than run on the one an administrative call happened to reach.
 *
 * <p><b>A control must be idempotent.</b> A replica applies a command at most once per process, but a replica that
 * restarts before acknowledging applies it again. <b>It must also be quick and local:</b> it runs on the replica's
 * poller thread with a timeout, and a slow handler delays every later command on that replica.
 *
 * @param <A> the arguments, bound from the submission's JSON body and validated before anything is recorded
 */
public interface ReplicaControl<A> {

    /**
     * The control's name, its path segment in {@code /admin/v1/replicas/controls/{control}}.
     *
     * @return a lower-case word
     */
    String name();

    /**
     * The type the submission body is bound to.
     *
     * @return the argument type
     */
    Class<A> argumentType();

    /**
     * The administrative operation that authorizes these arguments - one of the declaring module's own operations,
     * and it may depend on the arguments (clearing a security cache is privileged).
     *
     * @param arguments the validated arguments
     * @return the operation
     * @throws IllegalArgumentException when the arguments name something that does not exist; the submission is then
     *                                  refused with {@code 400}
     */
    AdminOperation operationFor(A arguments);

    /**
     * Every operation {@link #operationFor} can return, checked against the catalog at startup.
     *
     * @return the operations
     */
    Set<AdminOperation> possibleOperations();

    /**
     * Applies the control on this replica.
     *
     * @param arguments the arguments
     * @return an optional small per-replica result, serialized to at most 4 KiB
     * @throws RuntimeException to acknowledge failure
     */
    Optional<Object> apply(A arguments);
}
