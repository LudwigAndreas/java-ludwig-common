package ru.ludwigandreas.identity.directory.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.identity.directory.DirectoryPage;
import ru.ludwigandreas.identity.directory.DirectoryQuery;
import ru.ludwigandreas.identity.directory.IdentityDirectory;
import ru.ludwigandreas.identity.directory.config.DirectoryValidator;

class DirectoryValidationTest {

    private static IdentityDirectory directory(String source) {
        return new IdentityDirectory() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public DirectoryPage fetch(DirectoryQuery query) {
                return new DirectoryPage(List.of(), null, null);
            }
        };
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Validation.class);

    @Configuration(proxyBeanMethods = false)
    static class Validation {

        @Bean
        DirectoryValidator validator(ObjectProvider<IdentityDirectory> directories) {
            return new DirectoryValidator(directories);
        }
    }

    @Test
    @DisplayName("two directories for one source fail startup, naming the source")
    void twoDirectoriesForOneSource() {
        runner.withBean("a", IdentityDirectory.class, () -> directory("staff"))
                .withBean("b", IdentityDirectory.class, () -> directory("staff"))
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("'staff'"));
    }

    @Test
    @DisplayName("one directory per source starts")
    void oneDirectoryPerSource() {
        runner.withBean("a", IdentityDirectory.class, () -> directory("staff"))
                .withBean("b", IdentityDirectory.class, () -> directory("customers"))
                .run(context -> assertThat(context).hasNotFailed());
    }
}
