package ru.ludwigandreas.notification.service;

import java.util.UUID;
import ru.ludwigandreas.notification.service.model.NotificationCommand;
import ru.ludwigandreas.notification.service.model.NotificationRequestView;
import ru.ludwigandreas.notification.service.model.RenderRequest;
import ru.ludwigandreas.notification.service.model.RenderedPreview;

/**
 * The single entry point both ingress adapters converge on.
 *
 * <p>The Kafka listener and the REST controller translate their own wire formats into a
 * {@link NotificationCommand} and call {@link #submit}. Neither validates a business rule, resolves
 * a recipient, checks a preference or writes a row - because the moment an adapter starts deciding
 * something, the other adapter has to be taught the same rule, and one of them eventually is not.
 * That drift is invisible until somebody notices that notifications sent over Kafka respect quiet
 * hours and notifications sent over REST do not.
 */
public interface NotificationService {

    /**
     * Accepts a request and fans it out.
     *
     * <p>Idempotent when the command carries a key: a second submission of the same key returns the
     * first one's result, marked as a duplicate, and creates nothing.
     */
    NotificationRequestView submit(NotificationCommand command);

    /**
     * The request behind an id, with the deliveries it fanned out into.
     *
     * <p>The other half of an asynchronous ingress. {@code submit} answers 202 and a location, which
     * is only a useful answer if that location resolves - and a caller whose HTTP call timed out
     * after the request was written has no other way to find out what happened to it. Without this,
     * the idempotency key is the only handle a caller has, and re-submitting to read a status is a
     * poor way to ask a question.
     *
     * <p>Scoped like the delivery history it exposes: a caller sees requests in its own tenant unless
     * its role says otherwise. See {@code SecurityConfig} for the mapping and
     * {@code ludwig.security.data.policies} for who gets what.
     *
     * @throws ru.ludwigandreas.notification.service.exception.RequestNotFoundException if no such
     *         request exists
     */
    NotificationRequestView get(UUID id);

    /**
     * Renders a template without sending anything.
     *
     * <p>Through exactly the same resolver and the same strictness as a live send, so a preview that
     * succeeds is a guarantee that the send would - which is the only property that makes a preview
     * worth having instead of sending a test notification to a real person.
     */
    RenderedPreview preview(RenderRequest request);
}
