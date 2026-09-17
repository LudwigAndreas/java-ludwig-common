package ru.ludwigandreas.notification.service;

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
     * Renders a template without sending anything.
     *
     * <p>Through exactly the same resolver and the same strictness as a live send, so a preview that
     * succeeds is a guarantee that the send would - which is the only property that makes a preview
     * worth having instead of sending a test notification to a real person.
     */
    RenderedPreview preview(RenderRequest request);
}
