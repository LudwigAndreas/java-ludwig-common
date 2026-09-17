# syntax=docker/dockerfile:1.7
#
# Build context is the REPOSITORY ROOT, not this directory:
#
#     docker build -f notification-service/Dockerfile -t notification-service:1.0.0 .
#
# It has to be. This is a module of a multi-module Maven build whose siblings (db-core, web-core,
# security, outbox, ...) are not published to any registry the build could reach, so a context
# scoped to this directory could not resolve its own dependencies.
#
# Multi-stage: the build stage carries a JDK, Maven and the whole dependency cache; the runtime stage
# carries a JRE and one jar. Keeping them apart is not mainly about size - the build stage contains
# the source, the toolchain and any credentials Maven was given, none of which has business running
# in production.
#
# EVERY image is pinned by name, version AND sha256 digest. A tag can be re-pointed at different
# content, so a tag-only reference means what gets deployed is whatever the registry happens to serve
# that day - both a supply-chain hole and an unreproducible build. The digest is the identity; the
# tag beside it is there so a human can read which release it is, and the two move together.

# --- build -----------------------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS build

WORKDIR /build

# The poms first, in their own layer. They change far less often than the sources, so an ordinary
# code change reuses this layer instead of re-resolving every dependency in the reactor.
COPY pom.xml ./
COPY common-utils/pom.xml common-utils/
COPY odata-filter-spring-boot-starter/pom.xml odata-filter-spring-boot-starter/
COPY db-core/pom.xml db-core/
COPY outbox-spring-boot-starter/pom.xml outbox-spring-boot-starter/
COPY security-spring-boot-starter/pom.xml security-spring-boot-starter/
COPY identity-projection-spring-boot-starter/pom.xml identity-projection-spring-boot-starter/
COPY hot-reload-spring-boot-starter/pom.xml hot-reload-spring-boot-starter/
COPY web-core-spring-boot-starter/pom.xml web-core-spring-boot-starter/
COPY observability-spring-boot-starter/pom.xml observability-spring-boot-starter/
COPY architecture-rules/pom.xml architecture-rules/
COPY checkstyle-rules/pom.xml checkstyle-rules/
COPY notification-service/pom.xml notification-service/
# The example service is in the reactor and is not needed here, but Maven refuses to read a parent
# pom whose <modules> name a directory that is absent - so its pom comes along and its sources do
# not, which is what -pl ... -am then skips.
COPY crud-service-example/pom.xml crud-service-example/

# -pl ... -am builds this module and only the modules it actually needs, so the example service and
# anything else in the reactor never enter the image's build at all.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl notification-service -am -Dmaven.test.skip=true dependency:go-offline

COPY checkstyle-rules/src checkstyle-rules/src
COPY common-utils/src common-utils/src
COPY odata-filter-spring-boot-starter/src odata-filter-spring-boot-starter/src
COPY db-core/src db-core/src
COPY outbox-spring-boot-starter/src outbox-spring-boot-starter/src
COPY security-spring-boot-starter/src security-spring-boot-starter/src
COPY identity-projection-spring-boot-starter/src identity-projection-spring-boot-starter/src
COPY hot-reload-spring-boot-starter/src hot-reload-spring-boot-starter/src
COPY web-core-spring-boot-starter/src web-core-spring-boot-starter/src
COPY observability-spring-boot-starter/src observability-spring-boot-starter/src
COPY notification-service/src notification-service/src

# maven.test.skip rather than skipTests: skipTests still *compiles* the tests, which would drag the
# test-scoped architecture-rules jar and every test source into the image build for no benefit.
#
# The tests are not run here at all. They need Docker (Testcontainers) and a broker, which a build
# running inside a container cannot assume; CI runs `mvn verify` on the same commit before this image
# is built, and an image built from a commit whose tests never ran is a process problem rather than
# one a Dockerfile can fix.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl notification-service -am -Dmaven.test.skip=true package

# --- runtime ---------------------------------------------------------------------------------------
#
# The Debian-based JRE rather than an Alpine one: eclipse-temurin publishes no arm64 alpine variant,
# so an alpine base would build on an x86 CI machine and fail on an arm64 node pool or developer
# machine. A base image that only works on some of the architectures the estate runs is a build that
# breaks for one team and nobody else.
FROM eclipse-temurin:17.0.20_8-jre@sha256:23d7f52652b6b65b57eab85df9631741807929a68ff7e37c27104de6ac6690d5 AS runtime

# Non-root, with no home directory and no login shell. A JVM needs neither, and a container whose
# process can write to its own filesystem is one exploit away from persisting.
RUN groupadd --system --gid 10001 notification \
 && useradd --system --uid 10001 --gid notification --no-create-home \
            --shell /usr/sbin/nologin notification

WORKDIR /app
# Read-only and owned by root: the application user can execute the jar and cannot replace it.
COPY --from=build --chown=root:root --chmod=444 \
     /build/notification-service/target/notification-service.jar /app/app.jar

USER 10001:10001

# Container-aware JVM settings.
#
#   MaxRAMPercentage       - the JVM sizes the heap from the *cgroup* limit rather than the host's
#                            memory, which is what stops a 512Mi pod being OOM-killed by a heap the
#                            JVM sized for a 64Gi node. 75% leaves room for metaspace, thread stacks,
#                            direct buffers and the JIT's own allocations.
#   UseG1GC                - predictable pauses for a service whose work is many small units.
#   ExitOnOutOfMemoryError - a JVM out of heap cannot be trusted to keep dispatching, and a delivery
#                            it claimed but cannot settle is stranded until the lease expires. Dying
#                            lets the stale sweeper recover the leases and lets Kubernetes replace
#                            the pod, which is strictly better than limping.
#   file.encoding          - templates and messages are UTF-8; the platform default is not guaranteed.
#   java.security.egd      - non-blocking entropy, so startup does not stall on a drained /dev/random.
ENV JAVA_TOOL_OPTIONS="\
-XX:MaxRAMPercentage=75.0 \
-XX:+UseG1GC \
-XX:+ExitOnOutOfMemoryError \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/tmp \
-Dfile.encoding=UTF-8 \
-Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# Exec form, deliberately: it makes the JVM PID 1, so SIGTERM reaches it directly. Wrapped in a
# shell, the signal goes to the shell and the JVM is killed outright - skipping the graceful shutdown
# that drains the delivery poller and leaving every in-flight delivery CLAIMED until its lease
# expires. There is no HEALTHCHECK here either: Kubernetes drives the liveness and readiness probes
# on /actuator/health/*, and a second, differently-configured check inside the image is one more
# thing to keep in step.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
