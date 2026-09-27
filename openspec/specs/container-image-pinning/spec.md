# Container image pinning

## Purpose
Every container image this platform references is identified by name, version **and** `sha256`
digest, so that what runs is what was chosen. A tag can be re-pointed at different content by
whoever owns the registry namespace; a digest cannot.

## Requirements

### Requirement: Every image reference carries a digest
Every container image reference anywhere in this repository SHALL be written as
`<name>:<version>@sha256:<64 hex digits>`. This applies to test fixtures, jib configuration,
Dockerfiles, `docker run` examples, and both `README.md` and `README.ru.md`. A bare tag is not
acceptable anywhere.

#### Scenario: A pin in the shared test image resource omits its digest
- **WHEN** a property under `ludwig.test.image.*` in `test-support`'s `images.properties` has a
  value that does not match `\S+@sha256:[0-9a-f]{64}`
- **THEN** `ImagePinningTest.everyPinCarriesADigest` fails, and `mvn -pl test-support -am verify`
  fails with it

#### Scenario: The pinned resource is empty
- **WHEN** `images.properties` contains no `ludwig.test.image.*` keys
- **THEN** the pinning test fails rather than passing vacuously, because a resource that pins
  nothing would let every module get an unpinned image while the suite stayed green

### Requirement: Test images come from test-support
A module that needs a container for a test SHALL take it from `ru.ludwigandreas.testsupport.image.LudwigTestImages`
rather than constructing a `DockerImageName` of its own. Centralising the reference is what makes
the digest rule checkable in one place.

#### Scenario: A module asks for an image that is not pinned
- **WHEN** a caller invokes `LudwigTestImages.reference("mysql")` and no
  `ludwig.test.image.mysql` property exists
- **THEN** an `IllegalArgumentException` naming `ludwig.test.image.mysql` is thrown, rather than
  `null` being returned and failing later as an unrelated container startup error

#### Scenario: A digest-pinned name is used with a Testcontainers container class
- **WHEN** a digest-pinned image name such as `LudwigTestImages.POSTGRES` is handed to a
  Testcontainers container class that expects a known base image
- **THEN** it is recognised as compatible with its base image, because
  `asCompatibleSubstituteFor` is applied centrally at the pin — a name carrying a digest never
  parses as a plain repository name, and applying this per call site is something a call site
  forgets
