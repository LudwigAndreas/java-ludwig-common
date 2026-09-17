package ru.ludwigandreas.notification.service.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * Where a template lives: {@code <key>/<channel>/<locale>/<part>.ftl}.
 *
 * <p>Channel and locale are part of the address rather than parameters inside one template, because
 * they change the whole document and not a phrase in it - an email is a subject plus an HTML body
 * plus a text alternative, a chat message is one short line, and the Russian version of either is
 * not the English one with words substituted. A single template branching on channel and language
 * would be unreadable and would make a translator edit the same file as the front-end.
 *
 * <h2>Fallback</h2>
 *
 * <p>Resolution tries the exact locale, then the language without its region, then the configured
 * default. So {@code ru-RU} finds a {@code ru} template, and a locale with no translation at all
 * gets the default language in full rather than a half-translated document - which is the same rule
 * {@code web-core}'s {@code supported-locales} applies to problem messages.
 *
 * <p>FreeMarker's own localized lookup is deliberately switched off (see
 * {@link FreemarkerTemplateRenderer}); it appends {@code _ru} to a file name, which would collide
 * with this directory scheme and resolve templates by a rule nobody reading the directory could see.
 */
public record TemplateCoordinates(String templateKey, ChannelType channel, Locale locale) {

    /** Rendered into the subject line; absent for channels that have none. */
    public static final String SUBJECT = "subject";

    /** The rich part: HTML for email, the JSON or markdown body for chat and webhook. */
    public static final String BODY_HTML = "body.html";

    /** The plain-text alternative. Always rendered for email. */
    public static final String BODY_TEXT = "body.txt";

    private static final String EXTENSION = ".ftl";

    /** Candidates per part: the exact locale, its language, and the configured default. */
    private static final int MAX_CANDIDATES = 3;

    public TemplateCoordinates {
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("A template key is required");
        }
        if (channel == null) {
            throw new IllegalArgumentException("A channel is required");
        }
    }

    /**
     * Candidate template names for one part, most specific first.
     *
     * @param defaultLanguage the configured fallback language tag
     */
    public List<String> candidates(String part, String defaultLanguage) {
        List<String> tags = new ArrayList<>(MAX_CANDIDATES);
        if (locale != null && !locale.toLanguageTag().equals(Locale.ROOT.toLanguageTag())) {
            addIfAbsent(tags, locale.toLanguageTag().toLowerCase(Locale.ROOT));
            addIfAbsent(tags, locale.getLanguage().toLowerCase(Locale.ROOT));
        }
        addIfAbsent(tags, defaultLanguage.toLowerCase(Locale.ROOT));

        List<String> names = new ArrayList<>(tags.size());
        for (String tag : tags) {
            names.add(name(tag, part));
        }
        return names;
    }

    /** The locale tag a resolved template name carries, for reporting which variant was used. */
    public static String localeOf(String templateName) {
        String[] segments = templateName.split("/");
        // <key>/<channel>/<locale>/<part>.ftl - the locale is the segment before the file name.
        return segments.length < 2 ? "" : segments[segments.length - 2];
    }

    private String name(String localeTag, String part) {
        return templateKey + "/" + channel.name().toLowerCase(Locale.ROOT) + "/" + localeTag
                + "/" + part + EXTENSION;
    }

    private static void addIfAbsent(List<String> tags, String tag) {
        if (!tag.isBlank() && !tags.contains(tag)) {
            tags.add(tag);
        }
    }
}
