package ru.ludwigandreas.cache.shared;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A short, stable fingerprint of a value type's serialized shape.
 *
 * <h2>The problem it solves</h2>
 *
 * <p>The shared tier's key namespace carries a version, {@code ludwig:{app}:{cache}:v3:{key}}, so that a
 * rolling deploy is safe: v1 and v2 pods share one Redis, and if v2 changes the serialized shape of a
 * cached value, a v1 pod reading a v2 entry either throws or - worse - deserializes into something subtly
 * wrong, in a field that is silently absent.
 *
 * <p>The version works only if somebody remembers to bump it, and <b>a hand-maintained integer that
 * somebody forgets is worse than no versioning at all</b>: it converts a loud failure into a silent one,
 * because the reader is now confident it is looking at a compatible entry. Shipping a bare
 * {@code version:} field and hoping is therefore not an option, and this class is the alternative that was
 * chosen: <em>make forgetting loud</em>.
 *
 * <h2>How forgetting becomes loud</h2>
 *
 * <p>On the first use of a shared cache, its view writes this fingerprint to Redis under
 * {@code ludwig:{app}:{cache}:v{version}:__shape} with {@code SET NX}. On every later boot the recorded
 * fingerprint is compared with the running process's. A version reused with a differently-shaped value
 * type <b>fails startup</b>, naming the cache, both fingerprints and the property to change. The operator
 * bumps the version, which is the fix they would have applied anyway, three weeks earlier.
 *
 * <p>Deriving the version <em>from</em> the fingerprint - no integer at all - was the other candidate and
 * was rejected: it makes every incidental refactor of a value type silently abandon a full cache, so a
 * cosmetic field rename becomes a fleet-wide cold start nobody chose. An explicit version that fails loudly
 * when it is stale keeps the decision with the person deploying.
 *
 * <h2>What the fingerprint covers, and what it cannot</h2>
 *
 * <p>The type's name, and the name and declared type of each record component or non-static field, sorted
 * so that declaration order is irrelevant - reordering record components does not change the JSON. It
 * recurses into this platform's own types, since those are the ones that change, and stops at JDK and
 * third-party types, whose internals are not what a release of this service alters.
 *
 * <p>It cannot see a change of <em>meaning</em> at a constant shape: a {@code String status} whose allowed
 * values changed, or a {@code Duration} that used to be seconds. Those need the version bumped by hand,
 * which is why the version exists as well as the fingerprint rather than instead of it.
 */
public final class ValueShape {

    /** How many characters of the digest are kept. Enough to be unique, short enough to read in a log. */
    private static final int FINGERPRINT_LENGTH = 16;

    /** How deep the walk goes before it stops describing and starts trusting the name alone. */
    private static final int MAX_DEPTH = 6;

    /** Prefixes whose internals are this platform's own, and therefore worth describing. */
    private static final List<String> OWN_PACKAGES = List.of("ru.ludwigandreas.");

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private static final int LOW_NIBBLE = 0x0f;

    private static final int HALF_BYTE = 4;

    private ValueShape() {
    }

    /**
     * The fingerprint of one value type.
     *
     * @param valueType the cached value's runtime type
     * @return sixteen lowercase hex characters, stable across processes and releases for an unchanged shape
     */
    public static String of(Class<?> valueType) {
        StringBuilder description = new StringBuilder();
        describe(valueType, description, new HashSet<>(), 0);
        return digest(description.toString());
    }

    private static void describe(Class<?> type, StringBuilder out, Set<String> seen, int depth) {
        out.append(type.getName());
        if (depth >= MAX_DEPTH || !isOwnType(type) || !seen.add(type.getName())) {
            return;
        }
        out.append('{');
        for (String member : membersOf(type)) {
            out.append(member).append(';');
        }
        out.append('}');
        for (Class<?> memberType : memberTypesOf(type)) {
            describe(memberType, out, seen, depth + 1);
        }
    }

    /**
     * The members that make up the serialized shape, sorted.
     *
     * <p>Sorted because declaration order is not part of the shape - reordering two record components
     * changes no JSON document, and a fingerprint that flagged it would train people to bump the version
     * for changes that do not need it, which is how a safety belt stops being taken seriously.
     */
    private static Collection<String> membersOf(Class<?> type) {
        Set<String> members = new TreeSet<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                members.add(component.getName() + ':' + component.getType().getName());
            }
            return members;
        }
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                members.add(field.getName() + ':' + field.getType().getName());
            }
        }
        return members;
    }

    private static Collection<Class<?>> memberTypesOf(Class<?> type) {
        List<Class<?>> types = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                types.add(component.getType());
            }
            return types;
        }
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                types.add(field.getType());
            }
        }
        return types;
    }

    private static boolean isOwnType(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.isEnum()) {
            return false;
        }
        String name = type.getName();
        return OWN_PACKAGES.stream().anyMatch(name::startsWith);
    }

    private static String digest(String description) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(description.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(HEX_DIGITS[(b >> HALF_BYTE) & LOW_NIBBLE]).append(HEX_DIGITS[b & LOW_NIBBLE]);
            }
            return hex.substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
