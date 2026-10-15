package ru.ludwigandreas.grpc.client.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outbound tokens come from {@code outbound-token-core}, the one implementation both client
 * starters share. This module attaches a token to its own transport and does nothing else with it:
 * a second cache, a second minter or a second reader of the caller's token is the duplicate that
 * library exists to end, and would drift from it the first time either is fixed.
 */
class TokenOwnershipTest {

    private static final String ROOT = "ru.ludwigandreas.grpc.client";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("no class caches tokens: use ru.ludwigandreas.outboundtoken.CachingTokenSource")
    void noOwnTokenCache() {
        ArchRuleDefinition.noClasses()
                .should().haveSimpleNameEndingWith("TokenSource")
                .orShould().haveSimpleName("CachedToken")
                .because("the token cache is ru.ludwigandreas.outboundtoken.CachingTokenSource")
                .check(classes);
    }

    @Test
    @DisplayName("no class mints client-credentials tokens: use ClientCredentialsTokenMinter")
    void noOwnMinter() {
        ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.security.oauth2.client.endpoint."
                                + "DefaultClientCredentialsTokenResponseClient")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest")
                .because("the mint is ru.ludwigandreas.outboundtoken.ClientCredentialsTokenMinter")
                .check(classes);
    }

    @Test
    @DisplayName("no class reads the caller's token itself: use InboundBearerToken")
    void noOwnTokenReader() {
        ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.security.oauth2.core.AbstractOAuth2Token")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.security.oauth2.server.resource.authentication."
                                + "AbstractOAuth2TokenAuthenticationToken")
                .because("the reader is ru.ludwigandreas.outboundtoken.InboundBearerToken")
                .check(classes);
    }
}
