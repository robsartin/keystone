# Keystone Foundation — Plan 1: Build Skeleton + Domain + Application

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Maven build with code-quality, coverage, and mutation gates, and TDD the entire pure-Java domain + application layer for the keystone walking skeleton (Result, Money, AccountCode, Side, Posting, JournalEntry, PostJournalEntryService) with hexagonal layering enforced by ArchUnit.

**Architecture:** Single Maven module under `co.embracejoy.accounting.keystone`. No Spring Boot starters yet — Plan 1 is pure POJOs in `domain` and `application` packages. The Spring Boot parent POM is used solely for dependency management (BOM). Plan 2 introduces actual Spring Boot.

**Tech Stack:** Java 25, Maven 3.x via wrapper, Spring Boot parent 4.0.3 (BOM only), JUnit Jupiter 6, ArchUnit 1.4.1, JaCoCo 0.8.13 (line coverage ≥85%), PIT 1.20.0 (mutation ≥60%), Spotless (Google Java Format), Checkstyle 10.25.0.

**Pre-condition:** Working dir is `~/code/keystone`. The repo is already `git init`'d (default branch `main`) and contains `docs/superpowers/specs/2026-05-09-keystone-foundation-design.md` plus the spec amendment commit. No other files exist.

**Definition of done:** `./mvnw -B verify` is green from a cold cache. JaCoCo reports ≥85% line coverage. PIT reports ≥60% mutation coverage on `domain..` and `application..`. ArchUnit hexagonal rules pass. All commits are atomic and follow conventional-commit-ish messages.

---

## File Structure (all paths relative to `~/code/keystone`)

**Created in this plan:**

| Path | Responsibility |
|---|---|
| `pom.xml` | Maven build, BOM, plugins, gates |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | Maven wrapper |
| `.gitignore` | Ignore `target/`, IDE files, OS junk |
| `.editorconfig` | LF, UTF-8, 4-space Java |
| `LICENSE` | Apache 2.0 |
| `README.md` | Project pitch + how-to-run (skeleton in Plan 1, fleshed in Plan 3) |
| `CLAUDE.md` | AI workflow conventions (skeleton in Plan 1, fleshed in Plan 2/3) |
| `checkstyle.xml` | 750-line file, 30-line method, no star imports, braces required |
| `docs/adr/0000-template.md` | ADR template |
| `docs/adr/0001-record-decisions-in-adrs.md` | Meta-ADR |
| `docs/adr/0002-hexagonal-architecture.md` | Layering decision (with worked example) |
| `docs/adr/0003-money-as-integer-minor-units.md` | ISO 4217 + integer minor units |
| `docs/adr/0004-result-type-and-problem-details.md` | Result + ProblemDetails boundary |
| `docs/adr/0007-junit-jupiter-6.md` | JUnit 6 + ArchUnit JUnit Platform |
| `src/main/java/co/embracejoy/accounting/keystone/domain/shared/Result.java` | Sealed `Result<T, E>` |
| `src/main/java/.../domain/money/Money.java` | Integer-minor-unit Money record |
| `src/main/java/.../domain/money/MoneyError.java` | Sealed money errors |
| `src/main/java/.../domain/account/AccountCode.java` | Account identifier value object |
| `src/main/java/.../domain/journal/Side.java` | DEBIT \| CREDIT enum |
| `src/main/java/.../domain/journal/Posting.java` | Single posting record |
| `src/main/java/.../domain/journal/JournalEntry.java` | Journal entry aggregate with balance invariant |
| `src/main/java/.../domain/journal/JournalError.java` | Sealed journal errors |
| `src/main/java/.../domain/journal/JournalEntryRepository.java` | Port (interface) |
| `src/main/java/.../application/journal/PostJournalEntryService.java` | Use-case service |
| `src/test/java/.../domain/shared/ResultTest.java` | TDD for Result |
| `src/test/java/.../domain/money/MoneyTest.java` | TDD for Money |
| `src/test/java/.../domain/account/AccountCodeTest.java` | TDD for AccountCode |
| `src/test/java/.../domain/journal/PostingTest.java` | TDD for Posting |
| `src/test/java/.../domain/journal/JournalEntryTest.java` | TDD for JournalEntry |
| `src/test/java/.../application/journal/PostJournalEntryServiceTest.java` | TDD for service (with in-test fake repo) |
| `src/test/java/.../architecture/HexagonalArchitectureTest.java` | ArchUnit layer rules |

**Not created in Plan 1** (deferred): all Spring Boot infrastructure, web layer, JPA, observability, OpenAPI gates, Docker, CI workflow, GitHub provisioning.

---

## Task 1: Initialize Maven build and supporting files

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `.editorconfig`
- Create: `LICENSE`

- [ ] **Step 1: Write `.gitignore`**

Create `~/code/keystone/.gitignore` with:

```gitignore
# Maven
target/
!.mvn/wrapper/maven-wrapper.jar
.mvn/.develocity/

# IDE
.idea/
*.iml
.vscode/
.project
.classpath
.settings/

# OS
.DS_Store
Thumbs.db

# Logs / runtime
*.log
logs/
```

- [ ] **Step 2: Write `.editorconfig`**

Create `~/code/keystone/.editorconfig`:

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4

[*.{yml,yaml,json,xml}]
indent_size = 2

[*.md]
trim_trailing_whitespace = false
```

- [ ] **Step 3: Write `LICENSE`**

Create `~/code/keystone/LICENSE` containing the **full text** of the Apache License, Version 2.0 (the standard text from https://www.apache.org/licenses/LICENSE-2.0.txt). This is a verbatim copy; do not paraphrase. The copyright line at the bottom of the boilerplate notice (the one inside the appendix `[]` brackets) should read:

```
Copyright 2026 Rob Sartin
```

- [ ] **Step 4: Write `pom.xml`**

Create `~/code/keystone/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.3</version>
        <relativePath/>
    </parent>

    <groupId>co.embracejoy.accounting</groupId>
    <artifactId>keystone</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>Keystone</name>
    <description>General ledger keystone — Spring Boot foundation</description>

    <properties>
        <java.version>25</java.version>
        <maven.compiler.source>25</maven.compiler.source>
        <maven.compiler.target>25</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <!-- Force JUnit Jupiter 6. Spring Boot 4.0.3's BOM may already
             ship 6.x; this override is harmless if so, and forces 6.x
             if the BOM still points at 5.x. -->
        <junit-jupiter.version>6.0.0</junit-jupiter.version>

        <archunit.version>1.4.1</archunit.version>
        <jacoco.version>0.8.13</jacoco.version>
        <checkstyle.version>10.25.0</checkstyle.version>
        <spotless.version>2.46.0</spotless.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <version>${archunit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <release>${java.version}</release>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <useModulePath>false</useModulePath>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 5: Generate Maven wrapper**

Run from `~/code/keystone`:

```bash
mvn -N wrapper:wrapper -Dmaven=3.9.9
```

Expected: creates `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties`. Exit code 0.

If you don't have `mvn` on PATH, install via `brew install maven` first.

- [ ] **Step 6: Verify build wires up**

Run:

```bash
./mvnw -B -q compile
```

Expected: `BUILD SUCCESS`. There's nothing to compile yet, but Maven downloads the parent POM and resolves dependencies.

- [ ] **Step 7: Commit**

```bash
git add .gitignore .editorconfig LICENSE pom.xml mvnw mvnw.cmd .mvn
git commit -m "$(cat <<'EOF'
build: initialize Maven project with Spring Boot 4.0.3 BOM and Java 25

Spring Boot parent is BOM-only; no starters yet. JUnit Jupiter 6 forced
via property override. ArchUnit 1.4.1 on test classpath ready for
hexagonal rules in a later task.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Wire Spotless and Checkstyle

**Files:**
- Modify: `pom.xml`
- Create: `checkstyle.xml`

- [ ] **Step 1: Write `checkstyle.xml`**

Create `~/code/keystone/checkstyle.xml`:

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
        "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
        "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
    <property name="severity" value="error"/>
    <property name="fileExtensions" value="java"/>

    <module name="FileLength">
        <property name="max" value="750"/>
    </module>
    <module name="LineLength">
        <property name="max" value="120"/>
        <property name="ignorePattern" value="^package.*|^import.*|a href|href|http://|https://|ftp://"/>
    </module>
    <module name="NewlineAtEndOfFile"/>

    <module name="TreeWalker">
        <module name="AvoidStarImport"/>
        <module name="UnusedImports"/>
        <module name="RedundantImport"/>
        <module name="MethodLength">
            <property name="max" value="30"/>
            <property name="countEmpty" value="false"/>
        </module>
        <module name="NeedBraces"/>
        <module name="EmptyBlock">
            <property name="option" value="text"/>
        </module>
        <module name="LeftCurly"/>
        <module name="RightCurly"/>
        <module name="ParameterName"/>
        <module name="LocalVariableName"/>
        <module name="MemberName"/>
        <module name="MethodName"/>
        <module name="PackageName"/>
        <module name="TypeName"/>
        <module name="ConstantName"/>
        <module name="WhitespaceAround"/>
        <module name="EmptyStatement"/>
        <module name="EqualsHashCode"/>
        <module name="MissingSwitchDefault"/>
        <module name="SimplifyBooleanExpression"/>
        <module name="SimplifyBooleanReturn"/>
        <module name="OneStatementPerLine"/>
    </module>
</module>
```

- [ ] **Step 2: Add Spotless and Checkstyle plugins to `pom.xml`**

Open `pom.xml`. Inside `<build><plugins>...</plugins></build>`, **append** these two plugin blocks just before `</plugins>`:

```xml
            <plugin>
                <groupId>com.diffplug.spotless</groupId>
                <artifactId>spotless-maven-plugin</artifactId>
                <version>${spotless.version}</version>
                <configuration>
                    <java>
                        <googleJavaFormat>
                            <version>1.22.0</version>
                            <style>GOOGLE</style>
                        </googleJavaFormat>
                        <removeUnusedImports/>
                        <trimTrailingWhitespace/>
                        <endWithNewline/>
                    </java>
                </configuration>
                <executions>
                    <execution>
                        <id>spotless-check</id>
                        <phase>validate</phase>
                        <goals><goal>check</goal></goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <configuration>
                    <configLocation>${project.basedir}/checkstyle.xml</configLocation>
                    <consoleOutput>true</consoleOutput>
                    <failsOnError>true</failsOnError>
                    <includeTestSourceDirectory>true</includeTestSourceDirectory>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>com.puppycrawl.tools</groupId>
                        <artifactId>checkstyle</artifactId>
                        <version>${checkstyle.version}</version>
                    </dependency>
                </dependencies>
                <executions>
                    <execution>
                        <id>checkstyle-check</id>
                        <phase>validate</phase>
                        <goals><goal>check</goal></goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 3: Verify both plugins run**

Run:

```bash
./mvnw -B -q validate
```

Expected: `BUILD SUCCESS`. (Nothing to format/lint yet — both run cleanly.)

- [ ] **Step 4: Commit**

```bash
git add pom.xml checkstyle.xml
git commit -m "$(cat <<'EOF'
build: add Spotless (Google Java Format) and Checkstyle gates

Both bound to the validate phase so they fail fast before compilation.
Checkstyle enforces 750-line files, 30-line methods, no star imports,
required braces, and standard naming.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Stub README and CLAUDE.md

These are placeholders in Plan 1; they are fleshed out in Plans 2 and 3 as the corresponding capabilities arrive.

**Files:**
- Create: `README.md`
- Create: `CLAUDE.md`

- [ ] **Step 1: Write `README.md`**

```markdown
# Keystone

A general ledger built in Spring Boot. This repository is the **keystone** — the
foundation that the rest of the ledger will grow from. See
[the foundation design spec](docs/superpowers/specs/2026-05-09-keystone-foundation-design.md)
for the rationale and the full picture.

## Status

Plan 1 of 3 in progress: build skeleton + domain + application layer.

## Quick start (Plan 1)

```bash
./mvnw -B verify
```

Runs Spotless, Checkstyle, JUnit 6 unit tests, JaCoCo coverage check
(≥85% line), PIT mutation check (≥60% on domain + application), and
ArchUnit hexagonal rules.

## Architecture decisions

See [`docs/adr/`](docs/adr/) for the ADRs landed alongside the keystone.

## License

Apache 2.0 — see [LICENSE](LICENSE).
```

- [ ] **Step 2: Write `CLAUDE.md`**

```markdown
# CLAUDE.md

Conventions and AI-specific workflow for keystone. For full setup, see
[README.md](README.md).

## Quick reference

- `./mvnw -B verify` — full local gate (format, lint, test, coverage, mutation, arch rules)
- `./mvnw spotless:apply` — auto-format Java
- `./mvnw test` — unit tests only

## Architecture rules (ArchUnit-enforced)

Hexagonal architecture per [ADR-0002](docs/adr/0002-hexagonal-architecture.md).
Dependencies point inward; never outward.

- `domain/` — pure POJOs; imports nothing outside `java.*` and own packages
- `application/` — depends on `domain` only
- `infrastructure/` — depends on `domain` + `application` (added in Plan 2)

## Key conventions

- **Money is integers**, never `double` or `BigDecimal`. ISO 4217 via `java.util.Currency`. See [ADR-0003](docs/adr/0003-money-as-integer-minor-units.md).
- **Internal APIs return `Result<T, E>`**, not exceptions. Exceptions are reserved for true bugs. See [ADR-0004](docs/adr/0004-result-type-and-problem-details.md).
- **TDD always**: red → green → refactor → commit. Each commit shows the discipline.
- **Tests use `@DisplayName`** and method names of the form `should<Expected>When<Condition>`.
- **JaCoCo gate at 85% line coverage**; PIT gate at 60% mutation on `domain..` + `application..`.

## Code style

- Google Java Format via Spotless (run `./mvnw spotless:apply`)
- Checkstyle: 750-line file max, 30-line method max, no star imports, braces required
```

- [ ] **Step 3: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: add minimal README and CLAUDE.md

Both will be expanded in Plans 2 and 3 as more capabilities land.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: ADR template + ADR-0001 (record decisions in ADRs)

**Files:**
- Create: `docs/adr/0000-template.md`
- Create: `docs/adr/0001-record-decisions-in-adrs.md`

- [ ] **Step 1: Write the ADR template**

Create `docs/adr/0000-template.md`:

```markdown
# ADR-NNNN: <Short noun phrase>

- **Status:** Proposed | Accepted | Superseded by ADR-XXXX
- **Date:** YYYY-MM-DD

## Context

Why are we deciding this now? What forces are at play?

## Decision

What we are doing.

## Consequences

What becomes easier, what becomes harder, what we are committing to.
```

- [ ] **Step 2: Write ADR-0001**

Create `docs/adr/0001-record-decisions-in-adrs.md`:

```markdown
# ADR-0001: Record significant decisions in ADRs

- **Status:** Accepted
- **Date:** 2026-05-09

## Context

Significant architectural decisions accumulate over time. Without a paper
trail, future contributors (including future-me) re-litigate settled
choices, often without knowing the constraints that drove the original
decision.

## Decision

We use Architecture Decision Records, in Michael Nygard's format
(Context / Decision / Consequences), stored as Markdown files under
`docs/adr/`. Each ADR is numbered sequentially. ADRs are immutable once
Accepted — to change a decision, write a new ADR that supersedes the old
one, and update the old ADR's Status to "Superseded by ADR-XXXX".

ADRs are committed alongside the code that implements them, in the same
PR.

## Consequences

- Future contributors can quickly understand *why* the codebase is shaped
  the way it is.
- Reverting a decision requires writing the reasoning down, which raises
  the bar for casual changes to load-bearing choices.
- We accept the small overhead of writing ADRs for non-trivial decisions.
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/
git commit -m "$(cat <<'EOF'
docs(adr): 0001 record significant decisions in ADRs

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ADR-0002 (hexagonal architecture)

**Files:**
- Create: `docs/adr/0002-hexagonal-architecture.md`

- [ ] **Step 1: Write ADR-0002**

```markdown
# ADR-0002: Hexagonal architecture enforced by ArchUnit

- **Status:** Accepted
- **Date:** 2026-05-09

## Context

A general ledger's correctness lives in the domain. Coupling that domain
to Spring, JPA, or HTTP concerns makes the rules harder to test, harder
to reason about, and harder to evolve. We want a structure where the
domain can be exercised entirely with plain JUnit, with no Spring context
and no database, and where adapter swaps (e.g. JPA → event-sourced) do
not touch domain code.

## Decision

We adopt **hexagonal architecture** (a.k.a. ports and adapters), with
three packages under `co.embracejoy.accounting.keystone`:

- `domain` — pure POJOs. Value objects, aggregates, domain errors, and
  **port interfaces**. May import only `java.*` and its own
  sub-packages. No Spring, no JPA, no Jackson, no SLF4J types as fields.
- `application` — use-case services that orchestrate the domain through
  ports. May depend only on `domain`.
- `infrastructure` — adapters. Implements ports (driven adapters: JPA,
  in-memory) and exposes the application via driving adapters (HTTP
  controllers). May depend on `domain` and `application`.

These rules are enforced at build time by an ArchUnit test
(`HexagonalArchitectureTest`).

### Worked example: why infrastructure imports domain

It is a common confusion that "infrastructure depends on domain" sounds
backwards. It is not. The Dependency Inversion Principle is about
**which side owns the abstraction**, not about which file imports
which.

In our codebase:

- `domain.journal.JournalEntryRepository` is an **interface** (a *port*)
  declared in `domain`. The domain owns the abstraction.
- `infrastructure.persistence.journal.JpaJournalEntryRepository` is a
  **class** that `implements JournalEntryRepository`. To do that, the
  Java source file has `import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;`
  at the top — a compile-time dependency on `domain`.

Both arrows point the same way: the *abstraction* arrow points inward
(domain owns it), and the *file-level import* arrow also points inward
(infrastructure depends on domain). What would be **wrong** is the
opposite: domain importing from infrastructure.

## Consequences

- Domain tests are fast and need no Spring context.
- Replacing JPA with another persistence story is an adapter change; the
  domain does not move.
- ArchUnit fails the build on any layering violation, so we do not have
  to police imports by hand.
- We accept the modest ceremony of writing ports + adapters even for
  simple use cases. The benefit pays back when the codebase grows.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0002-hexagonal-architecture.md
git commit -m "$(cat <<'EOF'
docs(adr): 0002 hexagonal architecture enforced by ArchUnit

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: ADR-0007 (JUnit Jupiter 6 + ArchUnit on JUnit Platform)

ADR-0007 is written ahead of ADRs 0003–0004 because it justifies the test
toolchain we use to TDD them.

**Files:**
- Create: `docs/adr/0007-junit-jupiter-6.md`

- [ ] **Step 1: Write ADR-0007**

```markdown
# ADR-0007: JUnit Jupiter 6 with ArchUnit on the JUnit Platform

- **Status:** Accepted
- **Date:** 2026-05-09

## Context

We want the latest JUnit feature set (parameterized test improvements,
better extensions, modern assertions). JUnit Jupiter 6 is the current
generation. ArchUnit's official integration artifact is named
`archunit-junit5`, but it integrates with the **JUnit Platform**, not
Jupiter 5 specifically — both Jupiter 5 and Jupiter 6 run on the
Platform.

## Decision

- All unit and integration tests are written in **JUnit Jupiter 6**.
- The Spring Boot 4.0.3 parent POM is BOM-managed; we override
  `<junit-jupiter.version>` to `6.0.0` in `pom.xml`. If Spring Boot's
  bundled version is already 6.x, the override is harmless.
- ArchUnit tests use `archunit-junit5` (the artifact name is misleading
  but the engine works on the JUnit Platform alongside Jupiter 6).
- Surefire is configured with `<useModulePath>false</useModulePath>` to
  avoid module-path discovery surprises with Java 25.

## Consequences

- We get current Jupiter features and stay on a supported track.
- One small risk: if a third-party library hard-codes a Jupiter 5 API
  reference that Jupiter 6 has removed, we may need to upgrade or
  shim that library. We will deal with this case-by-case.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0007-junit-jupiter-6.md
git commit -m "$(cat <<'EOF'
docs(adr): 0007 JUnit Jupiter 6 with ArchUnit on the JUnit Platform

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: TDD `Result<T, E>` sealed type

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/shared/ResultTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/shared/Result.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/shared/ResultTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Result")
class ResultTest {

    @Test
    @DisplayName("success() wraps a value in a Success")
    void shouldWrapValueInSuccessWhenSuccessFactoryUsed() {
        Result<Integer, String> r = Result.success(42);
        assertInstanceOf(Result.Success.class, r);
        assertEquals(42, ((Result.Success<Integer, String>) r).value());
    }

    @Test
    @DisplayName("failure() wraps an error in a Failure")
    void shouldWrapErrorInFailureWhenFailureFactoryUsed() {
        Result<Integer, String> r = Result.failure("boom");
        assertInstanceOf(Result.Failure.class, r);
        assertEquals("boom", ((Result.Failure<Integer, String>) r).error());
    }

    @Test
    @DisplayName("map transforms the value of a Success")
    void shouldTransformValueWhenMapAppliedToSuccess() {
        Result<Integer, String> r = Result.<Integer, String>success(2).map(x -> x * 3);
        assertEquals(6, ((Result.Success<Integer, String>) r).value());
    }

    @Test
    @DisplayName("map leaves a Failure untouched")
    void shouldReturnSameFailureWhenMapAppliedToFailure() {
        Result<Integer, String> failure = Result.failure("nope");
        Result<Integer, String> mapped = failure.map(x -> x * 3);
        assertSame(failure, mapped);
    }

    @Test
    @DisplayName("flatMap chains Success into another Result")
    void shouldChainResultsWhenFlatMapAppliedToSuccess() {
        Result<Integer, String> r = Result.<Integer, String>success(2)
                .flatMap(x -> Result.success(x + 1));
        assertEquals(3, ((Result.Success<Integer, String>) r).value());
    }

    @Test
    @DisplayName("flatMap short-circuits on Failure")
    void shouldShortCircuitWhenFlatMapAppliedToFailure() {
        Result<Integer, String> failure = Result.failure("stop");
        Result<Integer, String> chained = failure.flatMap(x -> Result.success(x + 1));
        assertSame(failure, chained);
    }

    @Test
    @DisplayName("fold applies the success branch on Success")
    void shouldApplySuccessBranchWhenFoldAppliedToSuccess() {
        String s = Result.<Integer, String>success(7).fold(v -> "ok:" + v, e -> "err:" + e);
        assertEquals("ok:7", s);
    }

    @Test
    @DisplayName("fold applies the error branch on Failure")
    void shouldApplyErrorBranchWhenFoldAppliedToFailure() {
        String s = Result.<Integer, String>failure("bad").fold(v -> "ok:" + v, e -> "err:" + e);
        assertEquals("err:bad", s);
    }

    @Test
    @DisplayName("Result is sealed and only Success and Failure may implement it")
    void shouldOnlyPermitSuccessAndFailureAsImplementations() {
        assertEquals(2, Result.class.getPermittedSubclasses().length);
    }

    @Test
    @DisplayName("success rejects null mapper")
    void shouldThrowWhenMapPassedNullFunction() {
        assertThrows(NullPointerException.class, () -> Result.success(1).map(null));
    }
}
```

- [ ] **Step 2: Run the test and verify it fails to compile**

Run:

```bash
./mvnw -B test -pl . -Dtest=ResultTest 2>&1 | tail -20
```

Expected: compilation error including `cannot find symbol` for `Result`. Build fails.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/shared/Result.java`:

```java
package co.embracejoy.accounting.keystone.domain.shared;

import java.util.Objects;
import java.util.function.Function;

/**
 * A success-or-failure value used at internal API boundaries.
 *
 * <p>Reserved for expected, recoverable outcomes (validation, business-rule
 * violations). True bugs (NPE, IO crashes) still throw.
 *
 * @param <T> the success value type
 * @param <E> the error type
 */
public sealed interface Result<T, E> permits Result.Success, Result.Failure {

    static <T, E> Result<T, E> success(T value) {
        return new Success<>(value);
    }

    static <T, E> Result<T, E> failure(E error) {
        return new Failure<>(error);
    }

    <U> Result<U, E> map(Function<? super T, ? extends U> mapper);

    <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> mapper);

    <R> R fold(Function<? super T, ? extends R> onSuccess,
               Function<? super E, ? extends R> onFailure);

    record Success<T, E>(T value) implements Result<T, E> {
        @Override
        public <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return new Success<>(mapper.apply(value));
        }

        @Override
        public <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return mapper.apply(value);
        }

        @Override
        public <R> R fold(Function<? super T, ? extends R> onSuccess,
                          Function<? super E, ? extends R> onFailure) {
            Objects.requireNonNull(onSuccess, "onSuccess");
            return onSuccess.apply(value);
        }
    }

    record Failure<T, E>(E error) implements Result<T, E> {
        @Override
        @SuppressWarnings("unchecked")
        public <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return (Result<U, E>) this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return (Result<U, E>) this;
        }

        @Override
        public <R> R fold(Function<? super T, ? extends R> onSuccess,
                          Function<? super E, ? extends R> onFailure) {
            Objects.requireNonNull(onFailure, "onFailure");
            return onFailure.apply(error);
        }
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run:

```bash
./mvnw -B test -Dtest=ResultTest 2>&1 | tail -10
```

Expected: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Run Spotless apply (in case formatting drifts)**

```bash
./mvnw -B spotless:apply
```

Expected: `BUILD SUCCESS`. Either no changes or minor formatting tweaks.

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): Result<T, E> sealed type for internal API boundaries

map / flatMap / fold helpers on a sealed (Success | Failure) interface,
TDD'd with ten tests covering happy path, short-circuit, and null-arg
rejection.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: ADR-0003 + TDD `Money` and `MoneyError`

**Files:**
- Create: `docs/adr/0003-money-as-integer-minor-units.md`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/money/MoneyTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/money/Money.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/money/MoneyError.java`

- [ ] **Step 1: Write ADR-0003**

Create `docs/adr/0003-money-as-integer-minor-units.md`:

```markdown
# ADR-0003: Money as integer minor units, ISO 4217 via java.util.Currency

- **Status:** Accepted
- **Date:** 2026-05-09

## Context

Money in a general ledger must be exact. `double` is wrong (binary
floating-point cannot represent decimal cents exactly); `BigDecimal` is
correct but easy to misuse (developers forget to set scale or rounding
mode). Stripe, Shopify, and most payment systems represent money as
integer minor units of a known currency.

## Decision

`Money` is a record `Money(long minorUnits, Currency currency)` where
`Currency` is `java.util.Currency` (already ISO 4217 backed). One unit
of `minorUnits` equals the smallest representable amount in the given
currency:

- USD: 1 = $0.01 (cents)
- JPY: 1 = ¥1 (no minor units)
- BHD: 1 = 0.001 BHD (mils, three decimal places)

The number of minor units per major unit is `currency.getDefaultFractionDigits()`.

Arithmetic (`plus`, `minus`) returns `Result<Money, MoneyError>`:

- `MoneyError.CurrencyMismatch` when operands have different currencies.
- `MoneyError.Overflow` when `Math.addExact` / `Math.subtractExact`
  overflow `long`. (`long` provides ~9.2e18 minor units; we will fail
  loud rather than wrap silently.)

Display formatting (e.g. `1234` USD → `"$12.34"`) lives at the API
boundary, never in the domain.

We explicitly reject `double` and reject `BigDecimal` for now.

## Consequences

- Arithmetic is exact and fast.
- Multi-currency revaluation is deferred to a later ADR; for now,
  cross-currency operations fail.
- Multiplication and division are not added in this ADR. When we need
  them (e.g. proration, FX), we will add them with explicit rounding
  rules in a follow-up ADR.
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/co/embracejoy/accounting/keystone/domain/money/MoneyTest.java`:

```java
package co.embracejoy.accounting.keystone.domain.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Money")
class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    @DisplayName("constructor rejects null currency")
    void shouldThrowWhenCurrencyIsNull() {
        assertThrows(NullPointerException.class, () -> new Money(100L, null));
    }

    @Test
    @DisplayName("equality is value-based on amount and currency")
    void shouldBeEqualWhenAmountAndCurrencyMatch() {
        assertEquals(new Money(500L, USD), new Money(500L, USD));
    }

    @Test
    @DisplayName("isZero is true for zero amount")
    void shouldReturnTrueWhenAmountIsZero() {
        assertTrue(new Money(0L, USD).isZero());
    }

    @Test
    @DisplayName("isZero is false for non-zero amount")
    void shouldReturnFalseWhenAmountIsNonZero() {
        org.junit.jupiter.api.Assertions.assertFalse(new Money(1L, USD).isZero());
    }

    @Test
    @DisplayName("negate flips the sign")
    void shouldFlipSignWhenNegateCalled() {
        assertEquals(new Money(-100L, USD), new Money(100L, USD).negate());
    }

    @Test
    @DisplayName("plus sums same-currency amounts")
    void shouldSumWhenPlusOnSameCurrency() {
        Result<Money, MoneyError> r = new Money(100L, USD).plus(new Money(250L, USD));
        assertInstanceOf(Result.Success.class, r);
        assertEquals(new Money(350L, USD), ((Result.Success<Money, MoneyError>) r).value());
    }

    @Test
    @DisplayName("plus fails on currency mismatch")
    void shouldReturnCurrencyMismatchWhenPlusOnDifferentCurrencies() {
        Result<Money, MoneyError> r = new Money(100L, USD).plus(new Money(100L, EUR));
        assertInstanceOf(Result.Failure.class, r);
        MoneyError e = ((Result.Failure<Money, MoneyError>) r).error();
        assertInstanceOf(MoneyError.CurrencyMismatch.class, e);
        MoneyError.CurrencyMismatch cm = (MoneyError.CurrencyMismatch) e;
        assertEquals(USD, cm.expected());
        assertEquals(EUR, cm.actual());
    }

    @Test
    @DisplayName("plus fails on overflow")
    void shouldReturnOverflowWhenPlusExceedsLongRange() {
        Result<Money, MoneyError> r = new Money(Long.MAX_VALUE, USD).plus(new Money(1L, USD));
        assertInstanceOf(Result.Failure.class, r);
        assertInstanceOf(MoneyError.Overflow.class, ((Result.Failure<Money, MoneyError>) r).error());
    }

    @Test
    @DisplayName("minus subtracts same-currency amounts")
    void shouldSubtractWhenMinusOnSameCurrency() {
        Result<Money, MoneyError> r = new Money(500L, USD).minus(new Money(200L, USD));
        assertInstanceOf(Result.Success.class, r);
        assertEquals(new Money(300L, USD), ((Result.Success<Money, MoneyError>) r).value());
    }

    @Test
    @DisplayName("minus fails on currency mismatch")
    void shouldReturnCurrencyMismatchWhenMinusOnDifferentCurrencies() {
        Result<Money, MoneyError> r = new Money(100L, USD).minus(new Money(50L, EUR));
        assertInstanceOf(Result.Failure.class, r);
        assertInstanceOf(MoneyError.CurrencyMismatch.class, ((Result.Failure<Money, MoneyError>) r).error());
    }

    @Test
    @DisplayName("minus fails on overflow")
    void shouldReturnOverflowWhenMinusExceedsLongRange() {
        Result<Money, MoneyError> r = new Money(Long.MIN_VALUE, USD).minus(new Money(1L, USD));
        assertInstanceOf(Result.Failure.class, r);
        assertInstanceOf(MoneyError.Overflow.class, ((Result.Failure<Money, MoneyError>) r).error());
    }
}
```

- [ ] **Step 3: Run the test, verify compile failure**

```bash
./mvnw -B test -Dtest=MoneyTest 2>&1 | tail -10
```

Expected: compilation error (`cannot find symbol Money`).

- [ ] **Step 4: Write `MoneyError`**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/money/MoneyError.java`:

```java
package co.embracejoy.accounting.keystone.domain.money;

import java.util.Currency;

/** Errors that can arise from {@link Money} arithmetic. */
public sealed interface MoneyError {

    /** Operands had different currencies. */
    record CurrencyMismatch(Currency expected, Currency actual) implements MoneyError {}

    /** Result would not fit in {@code long}. */
    record Overflow() implements MoneyError {}
}
```

- [ ] **Step 5: Write `Money`**

Create `src/main/java/co/embracejoy/accounting/keystone/domain/money/Money.java`:

```java
package co.embracejoy.accounting.keystone.domain.money;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.Currency;
import java.util.Objects;

/**
 * A monetary amount represented as integer minor units of a known currency.
 *
 * <p>One {@code minorUnits} equals the smallest representable amount in the
 * given currency: cents for USD, yen for JPY, mils for BHD. See
 * {@code currency.getDefaultFractionDigits()} for the scale.
 */
public record Money(long minorUnits, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public Money negate() {
        return new Money(-minorUnits, currency);
    }

    public Result<Money, MoneyError> plus(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            return Result.failure(new MoneyError.CurrencyMismatch(currency, other.currency));
        }
        try {
            return Result.success(new Money(Math.addExact(minorUnits, other.minorUnits), currency));
        } catch (ArithmeticException ignored) {
            return Result.failure(new MoneyError.Overflow());
        }
    }

    public Result<Money, MoneyError> minus(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            return Result.failure(new MoneyError.CurrencyMismatch(currency, other.currency));
        }
        try {
            return Result.success(
                    new Money(Math.subtractExact(minorUnits, other.minorUnits), currency));
        } catch (ArithmeticException ignored) {
            return Result.failure(new MoneyError.Overflow());
        }
    }
}
```

- [ ] **Step 6: Run the test, verify pass**

```bash
./mvnw -B test -Dtest=MoneyTest 2>&1 | tail -10
```

Expected: `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 7: Apply Spotless**

```bash
./mvnw -B spotless:apply
```

- [ ] **Step 8: Commit**

```bash
git add docs/adr/0003-money-as-integer-minor-units.md src/
git commit -m "$(cat <<'EOF'
feat(domain): Money as integer minor units with Result-typed arithmetic

ISO 4217 via java.util.Currency. plus/minus return Result, with
CurrencyMismatch and Overflow as the error variants. Math.addExact /
subtractExact catch overflow rather than wrapping.

Captured in ADR-0003.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: TDD `AccountCode`

`AccountCode` is a typed string for the keystone phase. The full `Account`
aggregate (with type, normal side, hierarchy) lands in slice 2.

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/account/AccountCodeTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/account/AccountCode.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AccountCode")
class AccountCodeTest {

    @Test
    @DisplayName("rejects null value")
    void shouldThrowWhenValueIsNull() {
        assertThrows(NullPointerException.class, () -> new AccountCode(null));
    }

    @Test
    @DisplayName("rejects blank value")
    void shouldThrowWhenValueIsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new AccountCode("   "));
    }

    @Test
    @DisplayName("rejects empty value")
    void shouldThrowWhenValueIsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new AccountCode(""));
    }

    @Test
    @DisplayName("trims surrounding whitespace")
    void shouldTrimSurroundingWhitespaceWhenConstructed() {
        assertEquals(new AccountCode("1000"), new AccountCode("  1000  "));
    }

    @Test
    @DisplayName("equality is case-sensitive on the trimmed value")
    void shouldDifferByCaseWhenComparedAfterTrim() {
        assertEquals(new AccountCode("AR-CASH"), new AccountCode("AR-CASH"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                new AccountCode("AR-CASH"), new AccountCode("ar-cash"));
    }
}
```

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=AccountCodeTest 2>&1 | tail -10
```

Expected: `cannot find symbol AccountCode`.

- [ ] **Step 3: Implement `AccountCode`**

```java
package co.embracejoy.accounting.keystone.domain.account;

import java.util.Objects;

/**
 * A string-typed identifier for a ledger account in the keystone phase.
 *
 * <p>The full {@code Account} aggregate (type, normal side, hierarchy) is
 * deferred to a later slice; for now we carry just the code.
 */
public record AccountCode(String value) {

    public AccountCode {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("AccountCode value must not be blank");
        }
        value = trimmed;
    }
}
```

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=AccountCodeTest 2>&1 | tail -10
```

Expected: `Tests run: 5, Failures: 0`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): AccountCode value object

Typed string for the keystone phase; full Account aggregate deferred to
slice 2. Trims whitespace, rejects blank values, value-based equality.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Add `Side` enum

`Side` is a trivial sum type; we add it without a dedicated test.

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/Side.java`

- [ ] **Step 1: Write `Side`**

```java
package co.embracejoy.accounting.keystone.domain.journal;

/** Which side of the ledger a posting affects. */
public enum Side {
    DEBIT,
    CREDIT
}
```

- [ ] **Step 2: Verify compile**

```bash
./mvnw -B compile 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): Side enum (DEBIT | CREDIT)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: TDD `Posting`

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/PostingTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/Posting.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Posting")
class PostingTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final AccountCode CASH = new AccountCode("1000");

    @Test
    @DisplayName("rejects null account")
    void shouldThrowWhenAccountIsNull() {
        assertThrows(NullPointerException.class,
                () -> new Posting(null, Side.DEBIT, new Money(100L, USD)));
    }

    @Test
    @DisplayName("rejects null side")
    void shouldThrowWhenSideIsNull() {
        assertThrows(NullPointerException.class,
                () -> new Posting(CASH, null, new Money(100L, USD)));
    }

    @Test
    @DisplayName("rejects null amount")
    void shouldThrowWhenAmountIsNull() {
        assertThrows(NullPointerException.class,
                () -> new Posting(CASH, Side.DEBIT, null));
    }

    @Test
    @DisplayName("allows zero amount (memo posting)")
    void shouldAcceptWhenAmountIsZero() {
        Posting p = new Posting(CASH, Side.DEBIT, new Money(0L, USD));
        assertEquals(0L, p.amount().minorUnits());
    }

    @Test
    @DisplayName("rejects negative amount; sign is carried by Side")
    void shouldThrowWhenAmountIsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new Posting(CASH, Side.DEBIT, new Money(-1L, USD)));
    }

    @Test
    @DisplayName("equality is value-based")
    void shouldBeEqualWhenAllComponentsMatch() {
        Posting a = new Posting(CASH, Side.DEBIT, new Money(100L, USD));
        Posting b = new Posting(CASH, Side.DEBIT, new Money(100L, USD));
        assertEquals(a, b);
    }
}
```

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=PostingTest 2>&1 | tail -10
```

- [ ] **Step 3: Implement `Posting`**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Objects;

/**
 * A single debit or credit against an account.
 *
 * <p>Sign is carried by {@link Side}; the {@link Money} amount is
 * non-negative. Zero is allowed (memo postings).
 */
public record Posting(AccountCode account, Side side, Money amount) {

    public Posting {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        if (amount.minorUnits() < 0L) {
            throw new IllegalArgumentException(
                    "Posting amount must be non-negative; sign is carried by Side");
        }
    }
}
```

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=PostingTest 2>&1 | tail -10
```

Expected: `Tests run: 6, Failures: 0`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): Posting record (account, side, non-negative amount)

Allows zero amount for memo postings; rejects negative because Side
carries the sign.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: ADR-0004 + TDD `JournalEntry` and `JournalError`

**Files:**
- Create: `docs/adr/0004-result-type-and-problem-details.md`
- Create: `src/test/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalError.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntry.java`

- [ ] **Step 1: Write ADR-0004**

```markdown
# ADR-0004: Result type for internal APIs; ProblemDetails at HTTP boundary

- **Status:** Accepted
- **Date:** 2026-05-09

## Context

We want consistent error handling that:

- Makes failure modes explicit in the type system (callers can't
  forget to handle them).
- Distinguishes expected domain failures from true bugs.
- Translates uniformly to HTTP without each controller inventing its
  own error shape.

Throwing checked exceptions for validation pollutes call sites and
encourages catch-and-rethrow noise. Throwing unchecked exceptions
hides failure modes from the type system.

## Decision

- All internal API surfaces that can fail in expected ways return
  `Result<T, E>` (see ADR-0007 + the `Result` type in
  `domain.shared`). The error type `E` is a sealed interface (e.g.
  `MoneyError`, `JournalError`) so callers get exhaustive
  pattern matching.
- True bugs (NPE, IO crash, illegal state) still throw
  `RuntimeException`. ArchUnit forbids any public method whose return
  type is a `Throwable`.
- At the HTTP boundary (added in Plan 2), a single `ResultMapper`
  translates `Result.Failure<DomainError>` into RFC 9457
  ProblemDetails responses. Each `DomainError` variant maps to a
  stable problem `type` URI.

## Consequences

- Callers can compose Result chains with `map`/`flatMap` without
  try/catch noise.
- The HTTP error contract is stable and documented in OpenAPI as a
  single ProblemDetails schema.
- We accept the boilerplate of pattern-matching on Result at use
  sites. Java 25 sealed switch makes this readable.
```

- [ ] **Step 2: Write the failing test**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalEntry")
class JournalEntryTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final AccountCode CASH = new AccountCode("1000");
    private static final AccountCode EQUITY = new AccountCode("3000");
    private static final LocalDate TODAY = LocalDate.parse("2026-05-09");

    private static Posting debit(AccountCode a, long amt, Currency c) {
        return new Posting(a, Side.DEBIT, new Money(amt, c));
    }

    private static Posting credit(AccountCode a, long amt, Currency c) {
        return new Posting(a, Side.CREDIT, new Money(amt, c));
    }

    @Test
    @DisplayName("of() returns Failure(NoPostings) when postings list is empty")
    void shouldReturnNoPostingsWhenPostingsAreEmpty() {
        Result<JournalEntry, JournalError> r = JournalEntry.of(TODAY, "init", List.of());
        assertInstanceOf(Result.Failure.class, r);
        assertInstanceOf(JournalError.NoPostings.class,
                ((Result.Failure<JournalEntry, JournalError>) r).error());
    }

    @Test
    @DisplayName("of() returns Failure(MixedCurrencies) when postings span currencies")
    void shouldReturnMixedCurrenciesWhenCurrenciesDiffer() {
        Result<JournalEntry, JournalError> r = JournalEntry.of(TODAY, "x",
                List.of(debit(CASH, 100L, USD), credit(EQUITY, 100L, EUR)));
        assertInstanceOf(Result.Failure.class, r);
        assertInstanceOf(JournalError.MixedCurrencies.class,
                ((Result.Failure<JournalEntry, JournalError>) r).error());
    }

    @Test
    @DisplayName("of() returns Failure(Unbalanced) when debits != credits")
    void shouldReturnUnbalancedWhenDebitsAndCreditsDiffer() {
        Result<JournalEntry, JournalError> r = JournalEntry.of(TODAY, "x",
                List.of(debit(CASH, 100L, USD), credit(EQUITY, 90L, USD)));
        assertInstanceOf(Result.Failure.class, r);
        JournalError.Unbalanced u = (JournalError.Unbalanced)
                ((Result.Failure<JournalEntry, JournalError>) r).error();
        assertEquals(new Money(100L, USD), u.debits());
        assertEquals(new Money(90L, USD), u.credits());
    }

    @Test
    @DisplayName("of() returns Success when balanced")
    void shouldReturnSuccessWhenBalanced() {
        Result<JournalEntry, JournalError> r = JournalEntry.of(TODAY, "opening",
                List.of(debit(CASH, 10000L, USD), credit(EQUITY, 10000L, USD)));
        assertInstanceOf(Result.Success.class, r);
        JournalEntry je = ((Result.Success<JournalEntry, JournalError>) r).value();
        assertEquals(TODAY, je.occurredOn());
        assertEquals("opening", je.description());
        assertEquals(2, je.postings().size());
        assertEquals(USD, je.currency());
    }

    @Test
    @DisplayName("of() returns Success when balanced across multiple postings")
    void shouldReturnSuccessWhenBalancedAcrossManyPostings() {
        AccountCode receivable = new AccountCode("1100");
        Result<JournalEntry, JournalError> r = JournalEntry.of(TODAY, "split",
                List.of(
                        debit(CASH, 600L, USD),
                        debit(receivable, 400L, USD),
                        credit(EQUITY, 1000L, USD)));
        assertInstanceOf(Result.Success.class, r);
    }

    @Test
    @DisplayName("of() rejects null occurredOn")
    void shouldThrowWhenOccurredOnIsNull() {
        assertThrows(NullPointerException.class, () -> JournalEntry.of(null, "x",
                List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD))));
    }

    @Test
    @DisplayName("of() rejects null description")
    void shouldThrowWhenDescriptionIsNull() {
        assertThrows(NullPointerException.class, () -> JournalEntry.of(TODAY, null,
                List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD))));
    }

    @Test
    @DisplayName("of() rejects null postings list")
    void shouldThrowWhenPostingsListIsNull() {
        assertThrows(NullPointerException.class,
                () -> JournalEntry.of(TODAY, "x", null));
    }

    @Test
    @DisplayName("postings list is defensively copied and unmodifiable")
    void shouldExposeUnmodifiablePostings() {
        Result<JournalEntry, JournalError> r = JournalEntry.of(TODAY, "x",
                List.of(debit(CASH, 1L, USD), credit(EQUITY, 1L, USD)));
        JournalEntry je = ((Result.Success<JournalEntry, JournalError>) r).value();
        assertThrows(UnsupportedOperationException.class,
                () -> je.postings().add(debit(CASH, 5L, USD)));
    }
}
```

- [ ] **Step 3: Verify compile failure**

```bash
./mvnw -B test -Dtest=JournalEntryTest 2>&1 | tail -10
```

- [ ] **Step 4: Write `JournalError`**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.money.Money;
import java.util.Currency;
import java.util.Set;

/** Reasons a {@link JournalEntry} factory may refuse to construct an entry. */
public sealed interface JournalError {

    /** No postings supplied. */
    record NoPostings() implements JournalError {}

    /** Postings reference more than one currency. */
    record MixedCurrencies(Set<Currency> currencies) implements JournalError {}

    /** Sum of debits does not equal sum of credits. */
    record Unbalanced(Money debits, Money credits) implements JournalError {}
}
```

- [ ] **Step 5: Write `JournalEntry`**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.money.MoneyError;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A balanced double-entry journal entry.
 *
 * <p>Construct via {@link #of(LocalDate, String, List)}; the factory enforces
 * the invariants (non-empty, single-currency, balanced) and returns a
 * {@code Result} so callers handle failures explicitly.
 */
public record JournalEntry(
        LocalDate occurredOn, String description, Currency currency, List<Posting> postings) {

    public JournalEntry {
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(postings, "postings");
        postings = List.copyOf(postings);
    }

    public static Result<JournalEntry, JournalError> of(
            LocalDate occurredOn, String description, List<Posting> postings) {
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(postings, "postings");

        if (postings.isEmpty()) {
            return Result.failure(new JournalError.NoPostings());
        }

        Set<Currency> currencies = postings.stream()
                .map(p -> p.amount().currency())
                .collect(Collectors.toUnmodifiableSet());
        if (currencies.size() > 1) {
            return Result.failure(new JournalError.MixedCurrencies(currencies));
        }
        Currency currency = currencies.iterator().next();

        Money zero = new Money(0L, currency);
        Money debits = sum(postings, Side.DEBIT, zero);
        Money credits = sum(postings, Side.CREDIT, zero);

        if (debits.minorUnits() != credits.minorUnits()) {
            return Result.failure(new JournalError.Unbalanced(debits, credits));
        }

        return Result.success(new JournalEntry(occurredOn, description, currency, postings));
    }

    private static Money sum(List<Posting> postings, Side side, Money zero) {
        Money acc = zero;
        for (Posting p : postings) {
            if (p.side() == side) {
                Result<Money, MoneyError> next = acc.plus(p.amount());
                if (next instanceof Result.Success<Money, MoneyError> s) {
                    acc = s.value();
                } else {
                    throw new ArithmeticException("Posting sum overflowed " + side);
                }
            }
        }
        return acc;
    }
}
```

- [ ] **Step 6: Verify pass**

```bash
./mvnw -B test -Dtest=JournalEntryTest 2>&1 | tail -10
```

Expected: `Tests run: 9, Failures: 0`.

- [ ] **Step 7: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add docs/adr/0004-result-type-and-problem-details.md src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalEntry with balanced double-entry invariant

Sealed JournalError variants: NoPostings, MixedCurrencies, Unbalanced.
JournalEntry.of() returns Result<JournalEntry, JournalError>; defensively
copies postings into an unmodifiable list. Captured in ADR-0004.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Define `JournalEntryRepository` port

The port has no logic; the implementation arrives in Plan 2 (JPA) and the
service test in Task 14 uses an in-test fake.

**Files:**
- Create: `src/main/java/co/embracejoy/accounting/keystone/domain/journal/JournalEntryRepository.java`

- [ ] **Step 1: Write the port**

```java
package co.embracejoy.accounting.keystone.domain.journal;

import java.util.Optional;

/** Persistence port for {@link JournalEntry} aggregates. */
public interface JournalEntryRepository {

    /**
     * Persist the given entry and return it with any storage-assigned
     * identity attached. The keystone phase returns the entry unchanged;
     * Plan 2 introduces an identifier wrapper.
     */
    JournalEntry save(JournalEntry entry);

    /** Find an entry by its identifier (string for now; ULID later). */
    Optional<JournalEntry> findById(String id);
}
```

- [ ] **Step 2: Verify compile**

```bash
./mvnw -B compile 2>&1 | tail -5
```

- [ ] **Step 3: Commit**

```bash
git add src/
git commit -m "$(cat <<'EOF'
feat(domain): JournalEntryRepository port (interface)

Implementations land in Plan 2 (JPA + in-test fake).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: TDD `PostJournalEntryService`

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java`
- Create: `src/main/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryService.java`

- [ ] **Step 1: Write the failing test**

```java
package co.embracejoy.accounting.keystone.application.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostJournalEntryService")
class PostJournalEntryServiceTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final AccountCode CASH = new AccountCode("1000");
    private static final AccountCode EQUITY = new AccountCode("3000");
    private static final LocalDate TODAY = LocalDate.parse("2026-05-09");

    private static final class FakeRepo implements JournalEntryRepository {
        final List<JournalEntry> saved = new ArrayList<>();

        @Override
        public JournalEntry save(JournalEntry entry) {
            saved.add(entry);
            return entry;
        }

        @Override
        public Optional<JournalEntry> findById(String id) {
            return Optional.empty();
        }
    }

    private static Posting debit(AccountCode a, long amt) {
        return new Posting(a, Side.DEBIT, new Money(amt, USD));
    }

    private static Posting credit(AccountCode a, long amt) {
        return new Posting(a, Side.CREDIT, new Money(amt, USD));
    }

    @Test
    @DisplayName("persists and returns Success when request is valid")
    void shouldPersistAndReturnSuccessWhenRequestIsValid() {
        FakeRepo repo = new FakeRepo();
        PostJournalEntryService service = new PostJournalEntryService(repo);

        Result<JournalEntry, JournalError> r = service.post(
                TODAY, "opening", List.of(debit(CASH, 1000L), credit(EQUITY, 1000L)));

        assertInstanceOf(Result.Success.class, r);
        assertEquals(1, repo.saved.size());
        assertSame(((Result.Success<JournalEntry, JournalError>) r).value(), repo.saved.get(0));
    }

    @Test
    @DisplayName("returns Failure and does not persist when entry is unbalanced")
    void shouldReturnFailureAndNotPersistWhenUnbalanced() {
        FakeRepo repo = new FakeRepo();
        PostJournalEntryService service = new PostJournalEntryService(repo);

        Result<JournalEntry, JournalError> r = service.post(
                TODAY, "bad", List.of(debit(CASH, 1000L), credit(EQUITY, 999L)));

        assertInstanceOf(Result.Failure.class, r);
        assertInstanceOf(JournalError.Unbalanced.class,
                ((Result.Failure<JournalEntry, JournalError>) r).error());
        assertEquals(0, repo.saved.size());
    }

    @Test
    @DisplayName("returns Failure when postings are empty")
    void shouldReturnFailureWhenPostingsEmpty() {
        FakeRepo repo = new FakeRepo();
        PostJournalEntryService service = new PostJournalEntryService(repo);

        Result<JournalEntry, JournalError> r = service.post(TODAY, "empty", List.of());

        assertInstanceOf(Result.Failure.class, r);
        assertInstanceOf(JournalError.NoPostings.class,
                ((Result.Failure<JournalEntry, JournalError>) r).error());
        assertEquals(0, repo.saved.size());
    }
}
```

- [ ] **Step 2: Verify compile failure**

```bash
./mvnw -B test -Dtest=PostJournalEntryServiceTest 2>&1 | tail -10
```

- [ ] **Step 3: Implement the service**

```java
package co.embracejoy.accounting.keystone.application.journal;

import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryRepository;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Use case: post a balanced journal entry, persisting it through the repository port. */
public final class PostJournalEntryService {

    private final JournalEntryRepository repository;

    public PostJournalEntryService(JournalEntryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Result<JournalEntry, JournalError> post(
            LocalDate occurredOn, String description, List<Posting> postings) {
        return JournalEntry.of(occurredOn, description, postings).map(repository::save);
    }
}
```

- [ ] **Step 4: Verify pass**

```bash
./mvnw -B test -Dtest=PostJournalEntryServiceTest 2>&1 | tail -10
```

Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
feat(application): PostJournalEntryService

Composes JournalEntry.of() with the repository port; persists only on
Success, returns Failure unchanged. Three-test TDD with an in-test fake
repo.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: ArchUnit hexagonal architecture test

**Files:**
- Create: `src/test/java/co/embracejoy/accounting/keystone/architecture/HexagonalArchitectureTest.java`

- [ ] **Step 1: Write the test**

```java
package co.embracejoy.accounting.keystone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "co.embracejoy.accounting.keystone",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainDoesNotDependOnApplication =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..application..");

    @ArchTest
    static final ArchRule domainDoesNotDependOnInfrastructure =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnInfrastructure =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule domainDoesNotImportSpring =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule domainDoesNotImportJpa =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..");

    @ArchTest
    static final ArchRule domainDoesNotImportJackson =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule domainDoesNotImportSlf4j =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.slf4j..");

    @ArchTest
    static final ArchRule applicationDoesNotImportSpring =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule noPublicMethodReturnsThrowable =
            methods()
                    .that().arePublic()
                    .should().notHaveRawReturnType(Throwable.class);

    @ArchTest
    static final ArchRule classesAreInExpectedTopLevelPackages =
            classes()
                    .that().resideInAPackage("co.embracejoy.accounting.keystone..")
                    .should().resideInAnyPackage(
                            "co.embracejoy.accounting.keystone",
                            "co.embracejoy.accounting.keystone.domain..",
                            "co.embracejoy.accounting.keystone.application..",
                            "co.embracejoy.accounting.keystone.infrastructure..");
}
```

- [ ] **Step 2: Run the test**

```bash
./mvnw -B test -Dtest=HexagonalArchitectureTest 2>&1 | tail -15
```

Expected: `Tests run: 10, Failures: 0`. (ArchUnit lifts each `@ArchTest` field into a discovered test.)

- [ ] **Step 3: Apply Spotless and commit**

```bash
./mvnw -B spotless:apply
git add src/
git commit -m "$(cat <<'EOF'
test(architecture): ArchUnit hexagonal layering rules

Ten rules: domain/application isolation, no Spring/JPA/Jackson/SLF4J in
domain, no Spring in application, no public method returns Throwable
(Result discipline), package whitelist.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: Add JaCoCo coverage gate at 85%

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the JaCoCo plugin to `pom.xml`**

Append to `<build><plugins>...</plugins></build>` (just before `</plugins>`):

```xml
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>${jacoco.version}</version>
                <executions>
                    <execution>
                        <id>jacoco-prepare-agent</id>
                        <goals><goal>prepare-agent</goal></goals>
                    </execution>
                    <execution>
                        <id>jacoco-report</id>
                        <phase>test</phase>
                        <goals><goal>report</goal></goals>
                    </execution>
                    <execution>
                        <id>jacoco-check</id>
                        <phase>verify</phase>
                        <goals><goal>check</goal></goals>
                        <configuration>
                            <rules>
                                <rule>
                                    <element>BUNDLE</element>
                                    <limits>
                                        <limit>
                                            <counter>LINE</counter>
                                            <value>COVEREDRATIO</value>
                                            <minimum>0.85</minimum>
                                        </limit>
                                    </limits>
                                </rule>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 2: Run verify and inspect coverage**

```bash
./mvnw -B verify 2>&1 | tail -25
```

Expected: `BUILD SUCCESS`. JaCoCo report is at `target/site/jacoco/index.html`. If coverage is < 85%, the build will fail with a message naming the violated rule. Add tests to cover any gap before continuing — do NOT lower the threshold.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "$(cat <<'EOF'
build: enforce JaCoCo line coverage ≥85% on verify

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 17: Add PIT mutation gate at 60% on `domain..` and `application..`

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add PIT plugin properties**

In the `<properties>` block of `pom.xml`, add (preserving existing properties):

```xml
        <pitest.version>1.20.0</pitest.version>
        <pitest.junit5.version>1.2.3</pitest.junit5.version>
```

- [ ] **Step 2: Add the PIT plugin**

Append to `<build><plugins>...</plugins></build>` (just before `</plugins>`):

```xml
            <plugin>
                <groupId>org.pitest</groupId>
                <artifactId>pitest-maven</artifactId>
                <version>${pitest.version}</version>
                <dependencies>
                    <dependency>
                        <groupId>org.pitest</groupId>
                        <artifactId>pitest-junit5-plugin</artifactId>
                        <version>${pitest.junit5.version}</version>
                    </dependency>
                </dependencies>
                <configuration>
                    <targetClasses>
                        <param>co.embracejoy.accounting.keystone.domain.*</param>
                        <param>co.embracejoy.accounting.keystone.application.*</param>
                    </targetClasses>
                    <targetTests>
                        <param>co.embracejoy.accounting.keystone.*</param>
                    </targetTests>
                    <mutationThreshold>60</mutationThreshold>
                    <failWhenNoMutations>true</failWhenNoMutations>
                    <outputFormats>
                        <param>HTML</param>
                        <param>XML</param>
                    </outputFormats>
                </configuration>
                <executions>
                    <execution>
                        <id>pitest-mutation-coverage</id>
                        <phase>verify</phase>
                        <goals><goal>mutationCoverage</goal></goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 3: Run verify**

```bash
./mvnw -B verify 2>&1 | tail -25
```

Expected: `BUILD SUCCESS`, with PIT reporting a mutation score ≥60%. If under 60%, inspect the PIT HTML report at `target/pit-reports/index.html` to see surviving mutants. Add or strengthen tests to kill them — do NOT lower the threshold without reading the spec note in §7 of the design doc, and even then, make a deliberate choice.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "$(cat <<'EOF'
build: enforce PIT mutation coverage ≥60% on domain + application

Threshold is provisional per ADR-0004 / spec §7; tune up or down in a
follow-up PR after observing real numbers across the keystone codebase.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 18: Final verification from cold cache

**Files:** none

- [ ] **Step 1: Clean and run the full gate**

```bash
./mvnw -B clean verify 2>&1 | tail -40
```

Expected: `BUILD SUCCESS`. Output should show:

- Spotless check passing
- Checkstyle check passing
- All test classes passing (`ResultTest`, `MoneyTest`, `AccountCodeTest`, `PostingTest`, `JournalEntryTest`, `PostJournalEntryServiceTest`, `HexagonalArchitectureTest`)
- JaCoCo line coverage ≥ 85%
- PIT mutation coverage ≥ 60%

Total test count should be: 10 (Result) + 11 (Money) + 5 (AccountCode) + 6 (Posting) + 9 (JournalEntry) + 3 (Service) + 10 (ArchUnit) = **54 tests**.

- [ ] **Step 2: Inspect reports**

```bash
open target/site/jacoco/index.html
open target/pit-reports/*/index.html
```

Confirm coverage and mutation scores look healthy.

- [ ] **Step 3: Verify commit history is clean and atomic**

```bash
git log --oneline
```

Expected: roughly 17 commits, each one small and well-scoped. `git log --stat HEAD~17..` should not show any commit touching dramatically more files than its message implies.

- [ ] **Step 4: Plan 1 done — no new commit needed**

Plan 2 picks up next, introducing Spring Boot.

---

## Acceptance criteria for Plan 1

1. `./mvnw -B clean verify` is green from a cold cache.
2. JaCoCo line coverage on the bundle is ≥ 85%.
3. PIT mutation score on `domain..` + `application..` is ≥ 60%.
4. ArchUnit's nine hexagonal rules all pass.
5. `domain` package source files contain zero imports outside `java.*` and own packages (verify by spot-checking with `grep -R "^import " src/main/java/co/embracejoy/accounting/keystone/domain | grep -v "^.*: import java\\|^.*: import co.embracejoy.accounting.keystone.domain"`).
6. ADRs 0001, 0002, 0003, 0004, 0007 exist and are referenced from `CLAUDE.md` and/or `README.md`.
7. `git log --oneline` shows atomic, well-scoped commits with no `WIP` or amended messages.
