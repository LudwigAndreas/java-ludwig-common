package ru.ludwigandreas.job.core.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.job.core.approval.OnApprovalRequired;

class OnApprovalRequiredTest {

    @Configuration(proxyBeanMethods = false)
    @Conditional(OnApprovalRequired.class)
    static class Guarded {
        @Bean
        String marker() {
            return "on";
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Guarded.class);

    @Test
    @DisplayName("matches an indexed list and a comma-separated value, not an absent or empty one")
    void forms() {
        runner.withPropertyValues("ludwig.admin.approval.required[0]=outbox.privileged")
                .run(context -> assertThat(context).hasBean("marker"));
        runner.withPropertyValues("ludwig.admin.approval.required=outbox.privileged,identity.privileged")
                .run(context -> assertThat(context).hasBean("marker"));
        runner.run(context -> assertThat(context).doesNotHaveBean("marker"));
        runner.withPropertyValues("ludwig.admin.approval.required=")
                .run(context -> assertThat(context).doesNotHaveBean("marker"));
    }
}
