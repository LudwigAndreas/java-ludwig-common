package ru.ludwigandreas.grpc.client.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * A test slice for code that calls other services over gRPC: this starter's auto-configuration and
 * what it cannot run without, nothing else.
 *
 * <p>Point a client at an in-process server the test starts itself -
 * {@code ludwig.grpc.client.clients.<name>.target=in-process:<server name>} - and every call runs
 * the same pipeline as in production: deadline, resilience, credentials, typed failures, metrics
 * and audit. Further auto-configurations are added with {@code @ImportAutoConfiguration}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@OverrideAutoConfiguration(enabled = false)
@ImportAutoConfiguration
public @interface LudwigGrpcClientTest {

    /**
     * Properties for the test, as {@code key=value}.
     *
     * @return the properties
     */
    @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
    String[] properties() default {};
}
