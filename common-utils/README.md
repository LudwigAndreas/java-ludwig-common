# common-utils

***English** · [Русский](README.ru.md)*

The platform's general-purpose helpers. Today that is one thing: a retry wrapper for calls that fail
in ways worth trying again.

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>common-utils</artifactId>
</dependency>
```

```java
Charge charge = RetryingCall
        .with(new RetryPolicy()
                .retryOn(SocketTimeoutException.class, ConnectException.class)
                .withMaxRetries(3)
                .withDelay(200, TimeUnit.MILLISECONDS)
                .withIncreasingDelay(true))
        .withMetrics(meterRegistry, "payment-gateway-charge")
        .get(() -> gateway.charge(request));
```

---

## What belongs here, and what does not

This module is the one place in the platform with no domain at all: no Spring, no JPA, no web stack,
no opinion about what a service is. Its only dependency is an optional Micrometer, and it stays that
way on purpose — a module every other module could depend on is a module that must never drag
anything in.

The bar for adding something is therefore high. A helper belongs here when it is genuinely
general-purpose *and* has nowhere better to live. Anything that knows about persistence belongs in
`db-core`, anything about HTTP in `web-core-spring-boot-starter`, anything about identity in
`security-spring-boot-starter`. "Utils" is not a category; it is what a module is called when nobody
decided where its contents go, and the way to keep that from happening is to keep the module small
enough to read in one sitting.

---

## Retrying a call

Two types. `RetryPolicy` says *when* to retry and *how long* to wait; `RetryingCall` runs the
operation under that policy.

### The policy

| Method | Effect |
|---|---|
| `retryOn(Class…)` | retry when the call throws one of these, or a subclass |
| `retryIf(Predicate<Throwable>)` | retry when a predicate says so — for "this HTTP status but not that one" |
| `retryOnResult(Predicate<Object>)` | retry on a *successful* return whose value is unsatisfactory |
| `withMaxRetries(int)` | attempts *after* the first; `0`, the default, means no retry at all |
| `withDelay(long, TimeUnit)` | fixed wait between attempts |
| `withIncreasingDelay(boolean)` + `withDelayFactor(double)` | exponential backoff, default factor `2.0` |
| `withMaxDelay(long, TimeUnit)` | ceiling, so exponential growth cannot run away |
| `enabled(boolean)` | switch retrying off without unpicking the call site |

`retryOnResult` is the one worth explaining. Plenty of APIs report failure by returning something
rather than by throwing — an empty body, a status field, `null` from a cache that has not warmed up —
and without it every such call site grows a hand-written loop. With it, "retry while the answer is
not yet useful" is the same one-liner as "retry while the call keeps failing".

**Nothing is retried by default.** A fresh `RetryPolicy` has `maxRetries = 0` and no retryable
exceptions, so wrapping a call without configuring one changes nothing. That is deliberate: the
dangerous default here is the generous one. Retrying a call that is not idempotent turns one duplicate
charge into four, and a wrapper that did it unless told otherwise would be a trap.

### Running the call

```java
RetryingCall.with(policy).run(() -> sink.publish(event));          // no result
RetryingCall.with(policy).get(() -> client.fetch(id));             // a result
RetryingCall.with(policy).call(() -> callable.call());             // a java.util.concurrent.Callable
```

All three take a lambda that may throw a checked exception, which is what lets them wrap the calls
that actually need retrying without a `try`/`catch` at every site.

### What it throws

A failure that is not retryable, or one that survives every attempt, is rethrown **as it was**. A
`RuntimeException` comes back unchanged; a checked exception is wrapped in a `RuntimeException` that
names the attempt count. The wrapper never swallows anything and never substitutes a default — a
retry helper that returned `null` after exhausting its attempts would turn a loud failure into a
`NullPointerException` somewhere else entirely.

Exhausting the attempts on a *result* predicate — every call succeeded, none returned anything
acceptable — throws `RetryExhaustedException`, because there is no exception to rethrow and returning
the last unsatisfactory value would look like success.

It catches `Throwable`, `Error` included, rather than `Exception`. A general-purpose wrapper has to
*observe* everything the wrapped call can produce; the policy then decides what is retryable, and
anything not retried is rethrown rather than swallowed. Narrowing the catch would let an
`Error` bypass the listener and the metrics on its way out, which is exactly when an operator most
wants to see it.

### Interruption

An interrupt during a backoff sleep restores the thread's interrupt flag and throws. It does not
swallow the interrupt and carry on sleeping, which is how a shutdown turns into a hang.

---

## Watching it

Instrumentation is opt-in and Micrometer is an optional dependency — a call without
`withMetrics(...)` publishes nothing and a service without Micrometer on its classpath still compiles.

| Meter | Type | Tags |
|---|---|---|
| `retry.attempts` | counter, one increment per attempt | `operation` |
| `retry.call` | timer, one recording per invocation | `operation`, `outcome` (`success`/`failure`), `exception` |

`operation` is the name you pass, so it is a bounded, server-side vocabulary rather than anything a
caller controls. The pair is what makes the useful question answerable: `retry.attempts` divided by
`retry.call` count is the average number of tries an operation needs, and it rising is the earliest
warning that a dependency is degrading — well before it starts failing outright.

A `RetryListener` covers what metrics cannot:

```java
RetryingCall.with(policy)
        .withListener((attempt, exception, result) ->
                log.warn("Retry {} of the charge call", attempt, exception))
        .get(() -> gateway.charge(request));
```

It is called *before* each retry, with either the exception or the unsatisfactory result that caused
it — never both.

---

## Build and test

```bash
mvn -pl common-utils test
```

Seven tests: the policy's decisions (which exceptions, which results, how the delay grows and where
it is capped), the wrapper's rethrow behaviour, and the meters, asserted against a
`SimpleMeterRegistry` rather than a mock so the tag values are the ones a dashboard would actually
see.
