package ru.ludwigandreas.security.admin;

import java.util.Arrays;
import java.util.Optional;

/**
 * How much an administrative operation can change, and therefore who a service should let perform it.
 *
 * <p>Three tiers and no more, because three is what an operator reasons about when writing a grant block. The
 * tier is part of the operation id ({@code outbox.operate}), so a grant names it explicitly and no tier implies
 * another: holding {@code outbox.privileged} does not confer {@code outbox.read}.
 *
 * <p>Whether a module put an operation in the right tier cannot be checked mechanically - it is a judgement about
 * what the operation does, not a fact visible in bytecode or source text. It is a review rule instead: every
 * module that declares operations lists them, with their tier and what each does, in its README, and the table
 * is reviewed with the code.
 */
public enum AdminTier {

    /** Observes state and changes nothing. */
    READ("read"),

    /**
     * Changes state in a way that is safe to repeat and cannot put this service out of agreement with an external
     * system or change who may access what - a retry, a run, a requeue.
     */
    OPERATE("operate"),

    /**
     * Changes who may access what, can put this service out of agreement with an external system, or discards
     * work irrecoverably. A call at this tier must state a reason.
     */
    PRIVILEGED("privileged");

    private final String token;

    AdminTier(String token) {
        this.token = token;
    }

    /**
     * The tier as it is written in an operation id.
     *
     * @return {@code read}, {@code operate} or {@code privileged}
     */
    public String token() {
        return token;
    }

    /**
     * The tier an operation id's suffix names.
     *
     * @param token the part of an operation id after the dot
     * @return the tier, or empty when the token names none
     */
    public static Optional<AdminTier> fromToken(String token) {
        return Arrays.stream(values()).filter(tier -> tier.token.equals(token)).findFirst();
    }
}
