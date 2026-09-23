# checkstyle-rules

***English** · [Русский](README.ru.md)*

> The shared Checkstyle configuration: formatting that mirrors IntelliJ IDEA's defaults, plus the
> naming, source-level and Javadoc conventions that make unfamiliar code read like the rest of the
> repository. Runs on every module at `validate`, before the compiler.

## Why

A developer who has not shipped production Java yet writes code that compiles, passes its test and
still reads as "someone's first service": a braceless `if`, a swallowed exception, a `3600` in the
middle of an expression, `System.out.println` where a logger belongs, a Javadoc whose `@param` names
a parameter that was renamed two commits ago. None of that is caught by the compiler, and pointing
it out by hand in review costs a senior an hour a day and teaches nothing that sticks.

This module turns those conventions into build output: the same message, in the same place, every
time, seconds after the file is saved - and a build that stops until they are addressed.

Two design rules keep it from becoming the thing everybody disables:

1. **It never asks for something IntelliJ's default formatter would undo.** The project ships no
   custom IDE code-style scheme, so "the style" is what IntelliJ does out of the box: 4-space
   indent, 8-space continuation indent, a 120-column margin, K&R braces with `} else {` on one line,
   `case` indented inside `switch`, at most two consecutive blank lines. Every whitespace rule
   encodes exactly that, which makes **Reformat Code (`Ctrl+Alt+L` / `Cmd+Alt+L`) a valid fix for
   anything the build reports about layout** - and means the IDE can never introduce a violation.
2. **Every check earns its place by catching a real mistake.** Rules that only express taste were
   left out - see [what this configuration deliberately does not check](#what-this-configuration-deliberately-does-not-check),
   which lists them with the reasons, so nobody re-derives them.

## Quick start

Nothing to switch on. The root pom applies the plugin to every module, so:

```bash
mvn validate          # style only, a few seconds, no compilation
mvn verify            # style runs first, before the compiler and the tests
```

A failure names the file, the line, the rule and what to do:

```
[ERROR] .../OrderService.java:88:9: 'if' construct must use '{}'s. [NeedBraces]
[ERROR] .../OrderService.java:94:36: '3600' is a magic number. [MagicNumber]
[ERROR] .../OrderService.java:23: Line is longer than 120 characters (found 134). [LineLength]
```

The name in brackets is the rule. Search it in
[`checkstyle.xml`](src/main/resources/ru/ludwigandreas/checkstyle/checkstyle.xml) - every rule there
carries a comment saying what it is for, which is the answer to "why does the build care about this".

To skip it for one throwaway local run:

```bash
mvn verify -Dcheckstyle.skip=true
```

`checkstyle.skip` is for a local loop. A pipeline that sets it is a pipeline with no style gate.

## Which tool owns what

Three tools cover this repository and they are deliberately disjoint; when a rule could live in two
of them it goes to the one that can actually see the problem.

| Tool | Sees | Owns |
|---|---|---|
| [`architecture-rules`](../architecture-rules/README.md) (ArchUnit) | Compiled bytecode: types, members, dependencies | Structure: layering, package cycles, what may cross a boundary, where a framework may be used, module isolation, how many collaborators a class has |
| **`checkstyle-rules`** (this module) | Source text and its syntax tree | Formatting, naming, source-level conventions, Javadoc correctness, resource bundles |
| SonarQube | Dataflow across method bodies | Bugs and security: nullability, injection, crypto misuse, resource leaks, cognitive complexity, commented-out code |

Concretely, this is the module that implements the checks ArchUnit was
[explicitly not able to express](../architecture-rules/README.md#what-this-library-deliberately-does-not-check):
ASCII-only source content, and anything else that lives in the characters of a file rather than in
its compiled shape.

## What is checked

112 rules. The groups below say what each one is protecting; the configuration file itself carries
the full reasoning per rule.

### File-level invariants

| Rule | What it prevents |
|---|---|
| `NewlineAtEndOfFile` (lf) | A last line whose next edit shows up as a two-line diff |
| `FileTabCharacter` | A file that is only aligned in the editor that wrote it |
| `LineLength` (120) | Lines you cannot review side by side; package/import lines and bare URLs are exempt |
| `TrailingWhitespace`, `ConsecutiveBlankLines` | Invisible characters, and gaps past the two blank lines IntelliJ keeps |
| `FileLength` (2000) | A file that has stopped being one unit of anything |

### Resource bundles

Localization is first-class here - user-facing text lives in i18n bundles - so the bundles are held
together by the build:

| Rule | What it prevents |
|---|---|
| `UniqueProperties` | A duplicated key, where the second silently overwrites the first |
| `Translation` | A key added to `messages.properties` and forgotten in `messages_ru.properties`, which shows a Russian-speaking user the raw message code at runtime |

### Source text

| Rule | What it prevents |
|---|---|
| `NonAsciiSourceText` | Hard-coded user-facing text (and mojibake): non-ASCII belongs in a bundle. Suppressed in tests, where an assertion has to spell out the localized string it expects |
| `ConsoleOutput` | `System.out`/`System.err`: no level, no timestamp, no correlation id, and unswitchable in production |
| `PrintStackTrace` | A stack trace thrown at stderr and the problem declared handled |
| `AuthorTag` | `@author`, which git already knows and which is wrong after the first edit by someone else |
| `UntrackedTodo` (warning) | A `TODO` with no issue key - a wish, rather than something on a board |

### Imports

`AvoidStarImport`, `UnusedImports`, `RedundantImport`, `IllegalImport` - the last one rejects JDK
internals (`sun.*`, `jdk.internal.*`), Commons Lang 2, JUnit 3/4 APIs leaking into a JUnit 5
codebase, and logging APIs that bypass the SLF4J facade the observability module configures.

### Naming

The standard Java conventions, which are also what IntelliJ's own inspections expect: package, type,
method, member, parameter, local, constant and type-parameter names, plus `AbbreviationAsWordInName`
(no `parseXMLToDTO`). `log`/`logger` is an accepted constant name, and type parameters may be up to
three letters so `BaseRepository<E, ID>` is legal. Type *suffix* conventions ("a controller ends in
`Controller`") are not here - they are structural, and `architecture-rules` enforces them against
the bytecode.

### Whitespace and wrapping - the IntelliJ mirror

`Indentation` (4 / 8 / case 4 / throws 8), `WhitespaceAround`, `WhitespaceAfter`,
`NoWhitespaceBefore`, `NoWhitespaceAfter`, `ParenPad`, `TypecastParenPad`, `MethodParamPad`,
`GenericWhitespace`, `EmptyForIteratorPad`, `EmptyForInitializerPad`, `SeparatorWrap` (a wrapped
call chain leads with the dot; an argument list keeps the comma), `EmptyLineSeparator`,
`AnnotationLocation`. Every one of these is what Reformat Code produces.

### Blocks and braces

`LeftCurly`, `RightCurly` (`} else {` on one line), `NeedBraces`, `EmptyBlock`, `EmptyCatchBlock`,
`AvoidNestedBlocks`.

`NeedBraces` is the single most valuable rule in the file for someone new: a braceless `if` that
later grows a second statement is the textbook way to ship a bug that reads correctly, because the
indentation lies. `EmptyCatchBlock` is a close second - a swallowed exception is the hardest class
of production failure to diagnose, because nothing anywhere recorded it. If catching and doing
nothing really is right, name the variable `ignored` or `expected` and the rule steps aside.

### Coding conventions

Mistakes that compile: `StringLiteralEquality` (`==` on strings works in tests and fails on values
off the wire), `EqualsAvoidNull`, `EqualsHashCode` (one without the other breaks every `HashMap` the
type is ever put into, Hibernate's included), `CovariantEquals`, `MissingSwitchDefault`,
`FallThrough`, `DefaultComesLast`, `SimplifyBooleanExpression`, `SimplifyBooleanReturn`,
`EmptyStatement`, `ModifiedControlVariable`, `OneStatementPerLine`, `MultipleVariableDeclarations`,
`ArrayTypeStyle`, `UpperEll`, `ModifierOrder`, `RedundantModifier`, `ExplicitInitialization`,
`UnusedLocalVariable`, `UnnecessaryParentheses`, the `UnnecessarySemicolon*` family,
`IllegalInstantiation`, `IllegalCatch`/`IllegalThrows` (`Throwable` and `Error` only - a framework
boundary legitimately catches `Exception`), `NoFinalizer`, `NoClone`, `MagicNumber`, `HiddenField`,
`NestedIfDepth`, `NestedTryDepth`.

### Class design and size

`VisibilityModifier`, `FinalClass`, `HideUtilityClassConstructor` (Spring entry points exempt),
`InterfaceIsType`, `MutableException`, `OneTopLevelClass`, `OuterTypeFilename`,
`OverloadMethodsDeclarationOrder`, `MethodLength` (100), `ParameterNumber` (8),
`CyclomaticComplexity` (15), `ThrowsCount` (3). The ceilings are tripwires, not targets.

### Javadoc

Split in two on purpose:

- **Correctness is always an error.** `JavadocMethod` (a `@param` must name a parameter that exists,
  a `@return` must not be on a `void` method), `JavadocStyle` (well-formed HTML, a first sentence
  that ends in a period - it is what the tool lifts into every summary table),
  `InvalidJavadocPosition`, `NonEmptyAtclauseDescription`, `AtclauseOrder`, `JavadocContentLocation`.
  Documentation that lies is worse than none.
- **Presence is a warning** (see the tier below): `MissingJavadocType`, `MissingJavadocMethod`, and
  `JavadocSummaryPresent` for a Javadoc block that is nothing but tags.

A *missing* `@param` is deliberately not an error. Demanding a tag per parameter is what produces
`@param name the name` - a tag that satisfies the tool and tells the reader nothing.

## Severity tiers

| Property | Default | Covers |
|---|---|---|
| (everything else) | `error` | Fails the build at `validate` |
| `checkstyle.docs.severity` | `warning` | Missing Javadoc on public types and methods, Javadoc with no summary, untracked TODOs |
| `checkstyle.package.info.severity` | `ignore` | `JavadocPackage` - one `package-info.java` per package |

The documentation tier is reported but does not break the build, so the configuration can be adopted
without writing a documentation backlog first. Raise it once a module's public API is covered:

```bash
mvn verify -Dcheckstyle.docs.severity=error
```

Set it in the root pom to make it permanent, or to `ignore` to silence the tier entirely.

## Suppressing a check

All three forms below force you to name the rule; none of them can switch everything off.

```java
@SuppressWarnings("checkstyle:MagicNumber")                  // whole declaration
public int backoffSeconds() { ... }

// CHECKSTYLE.OFF: IllegalCatch - a retry wrapper has to observe every failure, Errors included
} catch (Throwable e) {
    // CHECKSTYLE.ON: IllegalCatch

// SUPPRESS CHECKSTYLE VisibilityModifier - injected by Hibernate, no constructor to inject through
@Autowired(required = false)
DbCoreMetrics metrics;                                       // covers this line and the next two
```

Always write the reason. A suppression with one is a decision a reviewer can agree with; a
suppression without one is the rule quietly deleted.

File-scoped suppressions live in
[`checkstyle-suppressions.xml`](src/main/resources/ru/ludwigandreas/checkstyle/checkstyle-suppressions.xml)
and are limited to two kinds of entry: generated sources (QueryDSL Q-types, MapStruct
implementations - code nobody can edit), and the specific checks whose *reason for existing* does
not hold in a test. Tests are otherwise held to the same standard as production code: they are read
far more often than they are written.

## IDE setup

Two things, both one-off:

1. **Nothing to import.** Use IntelliJ's default Java code style. That is the whole point of the
   configuration: `Ctrl+Alt+L` (Reformat Code) and `Ctrl+Alt+O` (Optimize Imports) always produce a
   file this build accepts.
2. **`.editorconfig` at the repository root** is picked up automatically (IntelliJ, VS Code,
   Eclipse). It restates the defaults so they travel with the repository, and it raises the one
   setting where IntelliJ's factory default and the convention genuinely disagree: the IDE collapses
   five imports from one package into a star import, which `AvoidStarImport` rejects, so the
   thresholds are raised out of reach.

Optional, and worth it: the **Checkstyle-IDEA** plugin, pointed at
`checkstyle-rules/src/main/resources/ru/ludwigandreas/checkstyle/checkstyle.xml`, with scan scope
"All sources including tests". It puts the same messages in the editor as you type, which is a much
shorter feedback loop than a build.

## Using it in another service

The configuration is published as a jar so a downstream service does not copy the XML:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-checkstyle-plugin</artifactId>
    <version>3.6.0</version>
    <dependencies>
        <dependency>
            <groupId>com.puppycrawl.tools</groupId>
            <artifactId>checkstyle</artifactId>
            <version>10.21.4</version>
        </dependency>
        <dependency>
            <groupId>ru.ludwigandreas</groupId>
            <artifactId>checkstyle-rules</artifactId>
            <version>1.0.0</version>
        </dependency>
    </dependencies>
    <configuration>
        <!-- Resolved from the plugin classpath, so no path into another repository. -->
        <configLocation>ru/ludwigandreas/checkstyle/checkstyle.xml</configLocation>
        <suppressionsLocation>ru/ludwigandreas/checkstyle/checkstyle-suppressions.xml</suppressionsLocation>
        <includeTestSourceDirectory>true</includeTestSourceDirectory>
        <violationSeverity>error</violationSeverity>
        <propertyExpansion>docs.severity=warning</propertyExpansion>
    </configuration>
    <executions>
        <execution>
            <id>checkstyle-validate</id>
            <phase>validate</phase>
            <goals><goal>check</goal></goals>
        </execution>
    </executions>
</plugin>
```

A service with its own suppressions points `suppressionsLocation` at its own file instead; the rules
themselves should not be forked. This repository is the exception - it references the files by path
rather than through the jar, because Maven resolves plugin dependencies from the local repository
and not from the reactor, so a jar reference would make a clean clone fail until someone had
installed this module first.

## What this configuration deliberately does not check

Some rules look like they belong here and do not. They were considered and rejected; the reasons are
recorded so nobody re-derives them.

| Not checked | Why, and where it belongs |
|---|---|
| Import **order** | IntelliJ's default layout puts third-party imports first and `javax`/`java` last - an IDE-specific ordering no Checkstyle group model reproduces exactly. Pinning one would mean every `Optimize Imports` reshuffles a file into a build failure. The IDE keeps it tidy for free |
| `OperatorWrap` (is a wrapped `&&` at the end of the line or the start of the next?) | The formatter only applies its answer when it re-wraps a line, and otherwise leaves an existing wrap alone - so either setting reports lines that Reformat Code does not fix. Pure taste; nothing breaks either way |
| `NPathComplexity` | Multiplies branches, so N sequential non-nested `if`s score 2^N: the clearest way to write a dispatch table scores in the thousands, while a genuinely tangled method with three nested conditions scores lower. `CyclomaticComplexity` and `NestedIfDepth` measure the real thing |
| `BooleanExpressionComplexity` | Counts operators, not entanglement: a list of eight `||`-ed supported types scores worse than `a && !b \|\| c && d`, which is the one a reader cannot evaluate. Cognitive complexity is SonarQube's, and it has a real measure of it |
| Commented-out code | Every regex for it ("a comment line ending in `;`") also matches prose that ends in a semicolon, and a rule that cries wolf on a comment explaining *why* teaches people to stop reading the output. SonarQube's `java:S125` parses the comment as Java instead |
| Class-level coupling (`ClassFanOutComplexity`, `ClassDataAbstractionCoupling`) | `architecture-rules` already bounds collaborators structurally. Running both means two tools reporting one problem with two different numbers |
| Type name suffixes, layering, forbidden dependencies | Structural. `architecture-rules` sees the bytecode and can express them properly |
| Security rules, nullability, resource leaks | Need dataflow analysis. SonarQube |

## Adopting it in a codebase that predates it

This repository was brought to zero violations before the gate was switched on, which is the
preferable order: a build that ships red teaches everyone to ignore it. In a larger codebase where
that is not practical:

1. Run `mvn checkstyle:checkstyle -Dcheckstyle.failOnViolation=false` and read
   `target/checkstyle-result.xml` per module.
2. Fix the mechanical groups first - they are safe and they are most of the list: line length,
   unused imports, missing braces, redundant initializers, Javadoc that no longer matches its
   signature.
3. For whatever is left, add **dated, file-scoped** entries to a service-local suppressions file
   rather than weakening a rule, so new code is held to the full standard from day one and the
   backlog is visible in one place.
