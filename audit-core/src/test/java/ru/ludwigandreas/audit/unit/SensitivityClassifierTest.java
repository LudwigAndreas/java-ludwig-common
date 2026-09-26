package ru.ludwigandreas.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.redaction.ConfiguredNamesSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.DeclaredSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.KeyNameSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.ProvenanceSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.SensitivityClassifier;

/**
 * Ported from {@code hot-reload-spring-boot-starter}'s {@code SecretRedactionTest}, before the
 * implementation it covered was touched: it is the regression net for the whole consolidation, and a
 * redaction bug introduced here writes credentials to a permanent table.
 */
class SensitivityClassifierTest {

    private static final String FILE = "file:/etc/app.properties";

    private final KeyNameSensitivityClassifier byName = new KeyNameSensitivityClassifier();
    private final ProvenanceSensitivityClassifier byProvenance = new ProvenanceSensitivityClassifier();

    @Test
    void keyNamesMatchingCommonSecretPatternsAreSensitive() {
        assertThat(byName.isSensitive(FILE, "db.password")).isTrue();
        assertThat(byName.isSensitive(FILE, "api-key")).isTrue();
        assertThat(byName.isSensitive(FILE, "authToken")).isTrue();
        assertThat(byName.isSensitive(FILE, "aws.credentials.id")).isTrue();
    }

    @Test
    void ordinaryKeysAreNotSensitive() {
        assertThat(byName.isSensitive(FILE, "app.name")).isFalse();
        assertThat(byName.isSensitive(FILE, "server.port")).isFalse();
    }

    @Test
    void everyVaultSourcedKeyIsSensitiveRegardlessOfName() {
        assertThat(byProvenance.isSensitive("vault:secret/myapp", "app.name")).isTrue();
        assertThat(byProvenance.isSensitive("vault-lease:database/creds/my-role", "username")).isTrue();
    }

    @Test
    void aFileSourcedOrdinaryKeyIsNotSensitiveByProvenance() {
        assertThat(byProvenance.isSensitive(FILE, "app.name")).isFalse();
    }

    /**
     * The composition rule the consolidation turns on: the union, never a priority order. A rule that let
     * one classifier clear what another flagged is a rule that leaks.
     */
    @Test
    @DisplayName("sensitive by provenance but innocuous by name, and the reverse, are both sensitive")
    void composesAsAUnion() {
        SensitivityClassifier composed = SensitivityClassifier.anyOf(byName, byProvenance);

        assertThat(composed.isSensitive("vault:secret/myapp", "timeout")).isTrue();
        assertThat(composed.isSensitive(FILE, "client_secret")).isTrue();
        assertThat(composed.isSensitive(FILE, "app.name")).isFalse();
    }

    @Test
    @DisplayName("a configured name is matched case-insensitively, because HTTP header names are")
    void configuredNamesAreCaseInsensitive() {
        ConfiguredNamesSensitivityClassifier configured =
                new ConfiguredNamesSensitivityClassifier(List.of("Authorization", "X-Partner-Key"));

        assertThat(configured.isSensitive("partner", "authorization")).isTrue();
        assertThat(configured.isSensitive("partner", "AUTHORIZATION")).isTrue();
        assertThat(configured.isSensitive("partner", "x-partner-key")).isTrue();
        assertThat(configured.isSensitive("partner", "accept")).isFalse();
    }

    /**
     * The only classifier that recognises personal rather than secret data: {@code mobile} matches no
     * secret-name heuristic and appears in no partner's header list.
     */
    @Test
    void aDeclaredSensitiveKeyIsSensitiveWhateverItIsCalled() {
        Set<String> pii = Set.of("profile.mobile");
        DeclaredSensitivityClassifier declared = new DeclaredSensitivityClassifier(pii::contains);

        assertThat(declared.isSensitive(null, "profile.mobile")).isTrue();
        assertThat(declared.isSensitive(null, "profile.nickname")).isFalse();
        assertThat(byName.isSensitive(null, "profile.mobile")).isFalse();
    }

    @Test
    void aClassifierWithNoRulesClassifiesNothing() {
        assertThat(SensitivityClassifier.none().isSensitive("vault:x", "password")).isFalse();
        assertThat(new ConfiguredNamesSensitivityClassifier(null).isSensitive("x", "password")).isFalse();
        assertThat(new ProvenanceSensitivityClassifier(List.of()).isSensitive("vault:x", "k")).isFalse();
        assertThat(new DeclaredSensitivityClassifier(null).isSensitive(null, "k")).isFalse();
    }
}
