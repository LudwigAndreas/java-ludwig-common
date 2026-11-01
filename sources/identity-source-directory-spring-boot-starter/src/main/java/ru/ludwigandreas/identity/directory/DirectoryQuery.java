package ru.ludwigandreas.identity.directory;

import java.time.Instant;

/**
 * What one {@link IdentityDirectory#fetch(DirectoryQuery)} call asks for.
 *
 * @param cursor       the previous page's next cursor, or null for the first page
 * @param changedSince only users changed at or after this, in ascending change order; null lists every user
 * @param pageSize     the largest page the engine wants
 */
public record DirectoryQuery(String cursor, Instant changedSince, int pageSize) {
}
