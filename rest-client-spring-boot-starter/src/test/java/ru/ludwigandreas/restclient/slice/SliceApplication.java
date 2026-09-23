package ru.ludwigandreas.restclient.slice;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application {@code @LudwigRestClientTest} anchors on.
 *
 * <p>{@code @SpringBootApplication} rather than a bare {@code @SpringBootConfiguration}, because the
 * interface scan needs the <em>auto-configuration packages</em>, and those are registered by
 * {@code @EnableAutoConfiguration} - which {@code @SpringBootApplication} includes and a plain
 * {@code @SpringBootConfiguration} does not. Every real service has one, so this is what a consuming
 * service already looks like; the slice still boots only this starter's auto-configurations, because
 * {@code @OverrideAutoConfiguration(enabled = false)} suppresses the import selector while leaving
 * the package registration in place.
 */
@SpringBootApplication
public class SliceApplication {
}
