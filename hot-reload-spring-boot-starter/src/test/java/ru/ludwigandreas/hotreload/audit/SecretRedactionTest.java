package ru.ludwigandreas.hotreload.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactionTest {

    @Test
    void keyNamesMatchingCommonSecretPatternsAreSensitive() {
        assertThat(SecretRedaction.isSensitive("file:/etc/app.properties", "db.password")).isTrue();
        assertThat(SecretRedaction.isSensitive("file:/etc/app.properties", "api-key")).isTrue();
        assertThat(SecretRedaction.isSensitive("file:/etc/app.properties", "authToken")).isTrue();
        assertThat(SecretRedaction.isSensitive("file:/etc/app.properties", "aws.credentials.id")).isTrue();
    }

    @Test
    void ordinaryKeysAreNotSensitive() {
        assertThat(SecretRedaction.isSensitive("file:/etc/app.properties", "app.name")).isFalse();
        assertThat(SecretRedaction.isSensitive("file:/etc/app.properties", "server.port")).isFalse();
    }

    @Test
    void everyVaultSourcedKeyIsSensitiveRegardlessOfName() {
        assertThat(SecretRedaction.isSensitive("vault:secret/myapp", "app.name")).isTrue();
        assertThat(SecretRedaction.isSensitive("vault-lease:database/creds/my-role", "username")).isTrue();
    }

    @Test
    void redactIfSensitiveOnlyMasksSensitiveValues() {
        assertThat(SecretRedaction.redactIfSensitive("file:/etc/app.properties", "app.name", "demo"))
                .isEqualTo("demo");
        assertThat(SecretRedaction.redactIfSensitive("file:/etc/app.properties", "db.password", "s3cr3t"))
                .isEqualTo(SecretRedaction.REDACTED);
        assertThat(SecretRedaction.redactIfSensitive("vault:secret/myapp", "anything", "value"))
                .isEqualTo(SecretRedaction.REDACTED);
    }

    @Test
    void redactIfSensitiveLeavesNullAlone() {
        assertThat(SecretRedaction.redactIfSensitive("vault:secret/myapp", "password", null)).isNull();
    }
}
