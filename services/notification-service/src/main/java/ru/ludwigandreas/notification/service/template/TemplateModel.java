package ru.ludwigandreas.notification.service.template;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shape of the data model every template renders against.
 *
 * <p>A template sees the caller's own variables at the top level, plus one reserved key. Stating the
 * reserved key here rather than spelling it at each call site is what keeps the two paths that build
 * a model - the fan-out, which resolves a real recipient, and the preview, which has none - producing
 * the same shape. When they diverge, the symptom is that previews of real templates fail while the
 * sends they were supposed to verify succeed, which makes the preview endpoint worse than useless:
 * it reports a problem that does not exist and stops being trusted.
 */
public final class TemplateModel {

    /**
     * The reserved key carrying the resolved recipient's own context.
     *
     * <p>Nested under one key rather than merged flat, so a caller sending a variable called
     * {@code locale} or {@code displayName} cannot silently overwrite what the renderer relies on.
     */
    public static final String RECIPIENT = "recipient";

    private TemplateModel() {
    }

    /** One recipient's context, as both the fan-out and the preview build it. */
    public static Map<String, Object> recipientContext(String userId, String displayName,
                                                       String locale, String timezone) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userId", userId);
        context.put("displayName", displayName);
        context.put("locale", locale);
        context.put("timezone", timezone);
        return context;
    }

    /**
     * A stand-in recipient for a preview, which is addressed to nobody.
     *
     * <p>Deliberately recognisable rather than realistic: an author looking at a preview should be in
     * no doubt that the name in it is sample data. Only used when the caller has not supplied a
     * {@code recipient} of their own - an author checking how a particular name renders can pass one.
     */
    public static Map<String, Object> sampleRecipient(String locale, String timezone) {
        return recipientContext("sample-user", "Sample Recipient", locale, timezone);
    }
}
