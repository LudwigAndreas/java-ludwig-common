package ru.ludwigandreas.cache.integration;

/**
 * {@link PartnerRecord} after a release changed its shape.
 *
 * <p>Exists so that "the same key namespace version, a differently-shaped value" is a real pair of types rather
 * than something simulated. A field added to a cached record is the ordinary way this happens, and it is exactly
 * the change that is harmless with the version bumped and silently wrong without it.
 *
 * @param name    unchanged
 * @param tier    unchanged
 * @param segment the field this release added
 */
record ChangedPartnerRecord(String name, int tier, String segment) {
}
