package ru.ludwigandreas.grpc.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.grpc.testing.TlsTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.grpc.server.integration.support.Calls;
import ru.ludwigandreas.grpc.server.server.GrpcServerLifecycle;

/**
 * TLS unless plaintext is doubly permitted, never plaintext or reflection in production, and every
 * refusal names the property to change.
 */
class GrpcServerTransportSecurityIT {

    /** Every interface, so the loopback exemption does not apply. */
    private static final ApplicationContextRunner EXPOSED = GrpcServerLifecycleIT.RUNNER
            .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class))
            .withUserConfiguration(EchoApplication.class)
            .withPropertyValues("ludwig.grpc.server.address=");

    private static final String[] TLS = {
        "spring.ssl.bundle.pem.grpc.keystore.certificate=classpath:certs/server1.pem",
        "spring.ssl.bundle.pem.grpc.keystore.private-key=classpath:certs/server1.key",
        "ludwig.grpc.server.tls.bundle=grpc"};

    @Test
    @DisplayName("plaintext on a non-loopback address with no switch fails and names both switches")
    void plaintextWithNoSwitch() {
        EXPOSED.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageContaining("ludwig.grpc.server.plaintext")
                .hasMessageContaining("ludwig.grpc.allow-plaintext")
                .hasMessageContaining("ludwig.grpc.server.tls.bundle"));
    }

    @Test
    @DisplayName("one plaintext switch is not enough, whichever one it is")
    void plaintextWithOneSwitch() {
        EXPOSED.withPropertyValues("ludwig.grpc.server.plaintext=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("ludwig.grpc.allow-plaintext"));
        EXPOSED.withPropertyValues("ludwig.grpc.allow-plaintext=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("ludwig.grpc.server.plaintext"));
    }

    @Test
    @DisplayName("both plaintext switches start the server outside production")
    void plaintextWithBothSwitches() {
        EXPOSED.withPropertyValues("ludwig.grpc.server.plaintext=true", "ludwig.grpc.allow-plaintext=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("both plaintext switches are still refused under a production profile, loopback included")
    void plaintextInProduction() {
        EXPOSED.withPropertyValues("ludwig.grpc.server.plaintext=true", "ludwig.grpc.allow-plaintext=true",
                        "spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("ludwig.grpc.server.tls.bundle").hasMessageContaining("prod"));
        EXPOSED.withPropertyValues("ludwig.grpc.server.address=127.0.0.1", "spring.profiles.active=production")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("production"));
    }

    @Test
    @DisplayName("the production profiles are configurable")
    void customProductionProfiles() {
        EXPOSED.withPropertyValues("ludwig.grpc.server.plaintext=true", "ludwig.grpc.allow-plaintext=true",
                        "spring.profiles.active=live", "ludwig.grpc.production-profiles=live")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("live"));
    }

    @Test
    @DisplayName("reflection under a production profile fails and names the property and the profile")
    void reflectionInProduction() {
        EXPOSED.withPropertyValues(TLS)
                .withPropertyValues("ludwig.grpc.server.reflection.enabled=true", "spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("ludwig.grpc.server.reflection.enabled").hasMessageContaining("prod"));
    }

    @Test
    @DisplayName("an unknown TLS bundle fails and names the property")
    void unknownBundle() {
        EXPOSED.withPropertyValues("ludwig.grpc.server.tls.bundle=missing")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("ludwig.grpc.server.tls.bundle").hasMessageContaining("missing"));
    }

    @Test
    @DisplayName("several mistakes are reported together")
    void everyProblemAtOnce() {
        EXPOSED.withPropertyValues("ludwig.grpc.server.reflection.enabled=true", "spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("2 problem(s)")
                        .hasMessageContaining("ludwig.grpc.server.reflection.enabled")
                        .hasMessageContaining("ludwig.grpc.server.tls.bundle"));
    }

    @Test
    @DisplayName("an executor with no thread is refused when the properties are bound")
    void executorBounds() {
        EXPOSED.withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration.class))
                .withPropertyValues("ludwig.grpc.server.executor.threads=0")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("executor.threads"));
    }

    @Test
    @DisplayName("with a TLS bundle the server starts in production and serves over TLS")
    void servesOverTls() {
        EXPOSED.withPropertyValues(TLS).withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            ManagedChannel channel = Grpc.newChannelBuilderForAddress("localhost",
                            context.getBean(GrpcServerLifecycle.class).port(),
                            TlsChannelCredentials.newBuilder().trustManager(TlsTesting.loadCert("ca.pem")).build())
                    .overrideAuthority("foo.test.google.fr")
                    .build();
            try {
                assertThat(Calls.stub(channel).echo(Calls.text("tls")).getText()).isEqualTo("tls");
            } finally {
                channel.shutdownNow();
            }
        });
    }
}
