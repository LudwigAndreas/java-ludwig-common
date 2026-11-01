package ru.ludwigandreas.security.operation;

/**
 * What an operation can do, and therefore what a call to it must bring.
 *
 * <p>Whether a module chose the right sensitivity cannot be checked mechanically - it is a judgement about what the
 * operation does. It is a review rule instead: every module lists its operations with their sensitivity in its
 * README, and the table is reviewed with the code.
 */
public enum Sensitivity {

    /** Observes state and changes nothing. */
    READ,

    /** Changes state in a way that is safe to repeat and cannot change who may do what - a retry, a run, a requeue. */
    CHANGE,

    /**
     * Can lose data, changes who may do what, or decides whether a dependency is called at all. A call must state a
     * reason, and is refused on a personal access token unless the deployment allows it.
     */
    PRIVILEGED
}
