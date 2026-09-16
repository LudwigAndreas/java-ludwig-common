/**
 * Miniature services the rules are evaluated against.
 *
 * <p>ru.ludwigandreas.archrules.fixture.good is a service that satisfies every rule; each
 * ru.ludwigandreas.archrules.fixture.bad sub-package breaks one group of them on purpose. A rule
 * is only proven by both halves: that it fails on the violation, and that it stays quiet on the
 * compliant code.
 *
 * <p>The frameworks these fixtures use - JPA, Spring, Spring Kafka, the AWS SDK - are test stubs
 * declared under their real package names rather than real dependencies. That is not a shortcut: the
 * rules address every framework type by fully qualified name precisely so that this library can stay
 * dependency-free, and stubbing the names is what proves that property holds.
 */
package ru.ludwigandreas.archrules.fixture;
