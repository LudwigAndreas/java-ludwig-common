package ru.ludwigandreas.notification.service.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A channel was requested that no {@code NotificationChannel} bean claims.
 *
 * <p>500, not 4xx: the channel constant is part of this service's own API, so if one is accepted by
 * validation and then has no implementation behind it, the fault is entirely ours - a bean that
 * failed its condition, or a constant added to the enum without its channel. Reporting it as a client
 * error would send the caller looking for a mistake they did not make.
 */
public class UnknownChannelException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public UnknownChannelException(String channel) {
        super(ProblemStatus.INTERNAL, "error.notification.channel.unknown", channel);
    }
}
