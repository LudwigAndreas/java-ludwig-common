package ru.ludwigandreas.security.admin;

import java.util.regex.Pattern;

/**
 * One administrative operation a module offers, as the consuming service grants it.
 *
 * <p>The id is {@code area.tier}: a lower-case word naming the module's administrative area, and one of the
 * three {@link AdminTier} tokens. A module declares the operation; it never names the role that may perform it -
 * that is the consuming service's decision, made in {@code ludwig.admin.grants}.
 *
 * @param id             {@code area.tier}, for example {@code outbox.operate}
 * @param area           the part before the dot
 * @param tier           the part after the dot
 * @param descriptionKey the i18n key of the operation's description, resolved in the caller's locale
 */
public record AdminOperation(String id, String area, AdminTier tier, String descriptionKey) {

    private static final Pattern AREA = Pattern.compile("[a-z][a-z0-9]*");

    /**
     * Validates that the id is well-formed and agrees with its area and tier.
     */
    public AdminOperation {
        if (id == null || area == null || tier == null || !AREA.matcher(area).matches()
                || !id.equals(area + "." + tier.token())) {
            throw new IllegalArgumentException("Administrative operation id '" + id + "' is not <area>.<tier>, where"
                    + " <area> is a lower-case word and <tier> is one of read, operate or privileged");
        }
        if (descriptionKey == null || descriptionKey.isBlank()) {
            throw new IllegalArgumentException("Administrative operation '" + id + "' needs a description key");
        }
    }

    /**
     * Parses an operation id.
     *
     * @param id             {@code area.tier}
     * @param descriptionKey the i18n key of the operation's description
     * @return the operation
     * @throws IllegalArgumentException when the id is not {@code area.tier}
     */
    public static AdminOperation of(String id, String descriptionKey) {
        int dot = id == null ? -1 : id.indexOf('.');
        String area = dot < 0 ? null : id.substring(0, dot);
        AdminTier tier = dot < 0 ? null : AdminTier.fromToken(id.substring(dot + 1)).orElse(null);
        return new AdminOperation(id, area, tier, descriptionKey);
    }
}
