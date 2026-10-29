package ru.ludwigandreas.security.principal;

import java.io.Serializable;
import java.util.List;

/**
 * The parties a request was made through, from its validated delegation claim (RFC 8693 {@code act}).
 *
 * <p>On {@link LudwigAuthentication}, not on {@link LudwigPrincipal}, for the reason {@link Credential} is: who the
 * caller is does not change with the channel they came through. It carries identity only - an acting party never
 * contributes an authority.
 *
 * @param chain the acting parties, the immediate one first, then each nested {@code act} in turn; never empty
 */
public record Delegation(List<String> chain) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Validates and copies the chain.
     */
    public Delegation {
        if (chain == null || chain.isEmpty() || chain.stream().anyMatch(each -> each == null || each.isBlank())) {
            throw new IllegalArgumentException("A delegation names at least one acting party, none blank");
        }
        chain = List.copyOf(chain);
    }

    /**
     * The party the request came through directly, e.g. an administrative UI.
     *
     * @return the head of the chain
     */
    public String actingParty() {
        return chain.get(0);
    }
}
