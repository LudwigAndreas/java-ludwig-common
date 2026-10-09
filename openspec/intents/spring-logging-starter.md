# Ludwig Logging Spring Boot Starter — Requirements

## 1. Purpose

Provide a standardized, production-ready logging configuration for all Ludwig-managed Spring Boot services. Services should receive useful logging defaults by adding the starter, without requiring individual logging configuration.

## 2. Build and Application Identity

- Generate and package build metadata into each application artifact.
- Include the following metadata where available:
  - Application/service name.
  - Application version.
  - Release identifier.
  - Git commit hash.
  - Git branch.
  - Build timestamp.
  - CI build number.
  - Dirty working-tree indicator.
- Obtain metadata during the build, not by querying Git at runtime.
- Emit a startup log event containing the application and build identity.
- Include service name, application version, and commit hash in every log event.
- Include deployment environment and instance identifier when available.
- Preserve build identity when the same artifact is promoted between environments.
- Fail CI validation for release artifacts missing mandatory metadata.

## 3. Console Logging

- Configure stdout logging by default.
- Use a human-readable format by default.
- Include timestamp, severity, logger name, thread, message, and exception stack trace.
- Include service identity and correlation metadata where appropriate.
- Support configurable log levels globally, by package, and by individual logger.
- Allow applications to override default logging settings without losing mandatory metadata.
- Support an optional structured JSON console format for environments that require machine-readable output.
- Write logs to stdout rather than stderr by default, except where an explicitly documented convention requires otherwise.

## 4. Local File Logging

- Enable local file logging by default, with the ability to disable it through configuration.
- Make the log directory configurable.
- Support time-based and size-based rotation.
- Compress rotated log files.
- Configure maximum file size, total storage budget, and retention period.
-
