# i18n bundles

## Purpose
User-facing text is never hard-coded, every starter ships its own bundle, and the locales stay in
lockstep. A key present in one locale and absent in another is how an API ends up answering half
in one language and half in another.
## Requirements
### Requirement: A starter ships its own bundle under a predictable name
A starter with user-facing text SHALL ship it at
`src/main/resources/i18n/ludwig-<module>-messages.properties` with a `_ru` sibling, and SHALL
contribute it to the shared pipeline as a `ProblemMessageBundle` bean.

#### Scenario: A starter adds user-facing text
- **WHEN** a starter needs text a caller will see
- **THEN** it adds the key to its own `ludwig-<module>-messages.properties`, adds the same key to
  `ludwig-<module>-messages_ru.properties`, and exposes the basename via a
  `ProblemMessageBundle` bean

### Requirement: Key sets match across locales
For every bundle, the key set of each locale variant SHALL be identical.

#### Scenario: A key is added to one locale only
- **WHEN** a key exists in `ludwig-<module>-messages.properties` but not in the `_ru` variant
- **THEN** that is a defect. A Russian-locale caller receives that one message in English while
  the rest of the response is translated

### Requirement: Text is never hard-coded in source
User-facing text SHALL NOT appear as a literal in Java source.

#### Scenario: A non-ASCII string literal is written into a source file
- **WHEN** a source file contains non-ASCII text, which in this repository means user-facing
  Russian text that belongs in a bundle
- **THEN** Checkstyle's `NonAsciiSourceText` rule fails the build at `validate`

### Requirement: Key resolution order is total and lets an application reword anything
A key SHALL be resolved from the application's `MessageSource` first, then from the contributed
bundles in their declared order, then from the caller's supplied default.

#### Scenario: An application rewords a module's message
- **WHEN** an application defines the same key in its own bundle as a module ships
- **THEN** the application's text is used, so a module ships working text without preventing an
  application from changing it

### Requirement: An unsupported locale gets the default in full
The resolved locale SHALL be limited to the configured `supported-locales` **whichever source
supplied it**, and the `MessageSource` SHALL NOT fall back to the server's own locale.

The locale no longer comes from `Accept-Language` alone. It comes from the chain the
`user-preference-context` capability defines — a stored preference, then `Accept-Language`, then
`ludwig.web.i18n.default-locale` — and the supported-locale restriction has to be applied after
resolution rather than inside the header parse, because a stored locale never passes through a header
at all.

#### Scenario: A caller asks for a language with no bundle
- **WHEN** a request carries an `Accept-Language` for an unsupported locale
- **THEN** the response is entirely in the default locale, rather than half-translated

#### Scenario: A user has stored a locale the service has no bundle for
- **WHEN** a user's stored `user.locale` is a locale absent from `supported-locales` — because the
  setting is written by a platform-wide settings screen that does not know which bundles this service
  ships
- **THEN** the response is entirely in the default locale. A stored preference must not be able to
  half-translate a response in a way an `Accept-Language` header cannot

#### Scenario: The container's locale differs from the default
- **WHEN** the JVM's default locale is not the API's default locale
- **THEN** the API's language is unaffected, because falling back to the server's own locale
  would make the API's language depend on how the container happens to be configured

### Requirement: Constraint messages are localized too
The validator SHALL be bound to the shared `MessageSource`, and SHALL resolve against the locale the
`user-preference-context` chain produced rather than against `Accept-Language` directly.

#### Scenario: A request is rejected by a Bean Validation constraint
- **WHEN** a constraint message is a bundle key such as
  `{catalog.validation.product.sku.required}`
- **THEN** it is rendered in the caller's resolved locale, rather than the response carrying a
  translated `title` alongside English field messages

#### Scenario: A user with a stored locale submits an invalid request
- **WHEN** the user's stored locale is `ru` and their browser sends `Accept-Language: en`
- **THEN** the `violations` entries are in Russian, because the validator reads
  `LocaleContextHolder`, which now carries the resolved locale rather than the header's

