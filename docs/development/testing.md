# Testing strategy

This guide covers how testing works in Keystone right now (post-Plan-1, pre-Plan-2). It is
written for a developer who knows JUnit broadly but is new to this codebase's conventions.

## Table of contents

1. [Test pyramid](#test-pyramid)
2. [TDD walkthrough](#tdd-walkthrough)
3. [Test naming and structure](#test-naming-and-structure)
4. [Coverage gates (JaCoCo)](#coverage-gates-jacoco)
5. [Mutation testing (PIT)](#mutation-testing-pit)
6. [ArchUnit rules](#archunit-rules)
7. [Testcontainers (Plan 2 preview)](#testcontainers-plan-2-preview)
8. [What not to test](#what-not-to-test)

---

## Test pyramid

```
       [  ArchUnit  ]      architecture rules — 10 rules, runs with Surefire
      [  Unit tests  ]     domain + application, pure JVM, no Spring context
  [ Integration tests ]    (Plan 2) @SpringBootTest + Testcontainers + JPA
```

**Unit tests** live under `src/test/java/.../domain/` and `.../application/`. They import nothing
outside `java.*`, JUnit, and production code. No Spring context, no database, no network. They run
in milliseconds and are the primary feedback loop during development. Write a unit test for every
domain invariant, every factory-method failure path, and every application-layer orchestration
decision.

**ArchUnit tests** live in `src/test/java/.../architecture/HexagonalArchitectureTest.java`. They
verify structural rules about the production bytecode — package layering, banned imports, and the
`Result`-over-`Throwable` discipline. You do not write ArchUnit tests for individual features; you
write them when you want to enforce a project-wide structural rule that no code review can reliably
catch by hand. See [ArchUnit rules](#archunit-rules) below.

**Integration tests** do not exist yet. Plan 2 introduces Failsafe + Testcontainers + JPA.
When they arrive they will use `@SpringBootTest`, `@Testcontainers`, and `@ServiceConnection` to
spin up a real Postgres 16 container. They belong under `src/test/java/...` with a naming
convention that Failsafe picks up (`*IT.java`). See
[Testcontainers (Plan 2 preview)](#testcontainers-plan-2-preview) for the high-level picture.

**Run all gates:**

```bash
./mvnw -B verify
```

This runs Spotless format check, Checkstyle, Surefire (unit + ArchUnit), JaCoCo coverage check,
and PIT mutation check in a single command. Unit tests alone:

```bash
./mvnw test
```

---

## TDD walkthrough

The discipline is **red → green → refactor → commit**. Each commit captures the cycle.

This walkthrough demonstrates how `Money.plus` and the `JournalEntry.of` balanced-entry invariant
could have been driven out. The code shown is the real production and test code verbatim.

### Step 1: write a failing test

Before writing any production code, write the test. Start with the happy path.

```java
// MoneyTest.java — the test we write first
@Test
@DisplayName("plus sums same-currency amounts")
void shouldSumWhenPlusOnSameCurrency() {
  Result<Money, MoneyError> r = new Money(100L, USD).plus(new Money(250L, USD));
  assertInstanceOf(Result.Success.class, r);
  assertEquals(new Money(350L, USD), ((Result.Success<Money, MoneyError>) r).value());
}
```

This does not compile yet because `Money.plus` does not exist. That is the first failure — a
compile error is a red test in disguise.

### Step 2: write the minimum production code to make it compile

Add `plus` to `Money`:

```java
// Money.java (minimal skeleton — still failing)
public Result<Money, MoneyError> plus(Money other) {
  return Result.success(new Money(minorUnits + other.minorUnits, currency));
}
```

The test now compiles and passes for the happy path.

### Step 3: add the failure-path tests

Now drive out the error cases one at a time:

```java
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
```

Each test is red until you extend `plus` to handle that case. The final production implementation
in [`src/main/java/.../domain/money/Money.java`](../../src/main/java/co/embracejoy/accounting/keystone/domain/money/Money.java):

```java
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
```

### Step 4: the invariant test for JournalEntry

The balanced-entry rule follows the same pattern. Write the failure test first:

```java
@Test
@DisplayName("of() returns Failure(Unbalanced) when debits != credits")
void shouldReturnUnbalancedWhenDebitsAndCreditsDiffer() {
  Result<JournalEntry, JournalError> r =
      JournalEntry.of(TODAY, "x", List.of(debit(CASH, 100L, USD), credit(EQUITY, 90L, USD)));
  assertInstanceOf(Result.Failure.class, r);
  JournalError.Unbalanced u =
      (JournalError.Unbalanced) ((Result.Failure<JournalEntry, JournalError>) r).error();
  assertEquals(new Money(100L, USD), u.debits());
  assertEquals(new Money(90L, USD), u.credits());
}
```

Then the happy path:

```java
@Test
@DisplayName("of() returns Success when balanced")
void shouldReturnSuccessWhenBalanced() {
  Result<JournalEntry, JournalError> r =
      JournalEntry.of(
          TODAY, "opening", List.of(debit(CASH, 10000L, USD), credit(EQUITY, 10000L, USD)));
  assertInstanceOf(Result.Success.class, r);
  JournalEntry je = ((Result.Success<JournalEntry, JournalError>) r).value();
  assertEquals(TODAY, je.occurredOn());
  assertEquals("opening", je.description());
  assertEquals(2, je.postings().size());
  assertEquals(USD, je.currency());
}
```

Each test drives out exactly one production behaviour. No test is written after the production code
already passes it.

### Step 5: refactor

Once all tests are green, look for duplication and clarity. The `debit`/`credit` helpers in
`JournalEntryTest` are an example of this step — they reduce repetition without hiding intent.

For error handling conventions see [ADR-0004](../adr/0004-result-type-and-problem-details.md). The
`Result` type ensures callers handle failure paths explicitly; do not throw checked exceptions for
domain validation.

---

## Test naming and structure

### Class-level `@DisplayName`

Every test class carries `@DisplayName` with the name of the class under test:

```java
@DisplayName("Money")
class MoneyTest { ... }

@DisplayName("JournalEntry")
class JournalEntryTest { ... }

@DisplayName("PostJournalEntryService")
class PostJournalEntryServiceTest { ... }
```

This appears in the Surefire XML, IDEs, and build output.

### Method-level `@DisplayName` and the naming convention

Every test method has:

- A human-readable `@DisplayName` string that describes the expected behaviour.
- A method name that follows `should<Expected>When<Condition>`.

The two complement each other. The display name is prose for humans in reports; the method name is
searchable in the IDE and grep-friendly.

```java
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
```

### Given / when / then

Each test body is arranged in given / when / then order. We do not add `// given`, `// when`,
`// then` comments — the structure is implicit from the names and the visual separation of
statements. Setup code (fixed constants, helper methods) moves to static fields or private
factory methods at the top of the class.

Example from
[`PostJournalEntryServiceTest.java`](../../src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java):

```java
@Test
@DisplayName("persists and returns Success when request is valid")
void shouldPersistAndReturnSuccessWhenRequestIsValid() {
  FakeRepo repo = new FakeRepo();
  PostJournalEntryService service = new PostJournalEntryService(repo);

  Result<JournalEntry, JournalError> r =
      service.post(TODAY, "opening", List.of(debit(CASH, 1000L), credit(EQUITY, 1000L)));

  assertInstanceOf(Result.Success.class, r);
  assertEquals(1, repo.saved.size());
  assertSame(((Result.Success<JournalEntry, JournalError>) r).value(), repo.saved.get(0));
}
```

The first two lines are setup (given). The `service.post(...)` call is the action (when). The
three assertions are the verification (then).

### Fakes over mocks

Application-layer tests use hand-written fakes rather than a mocking library. `FakeRepo` in
`PostJournalEntryServiceTest` is a complete, inner-class implementation of
`JournalEntryRepository` that records calls in an `ArrayList`. This keeps tests readable and avoids
mock-framework coupling. See the full class in
[`PostJournalEntryServiceTest.java`](../../src/test/java/co/embracejoy/accounting/keystone/application/journal/PostJournalEntryServiceTest.java).

---

## Coverage gates (JaCoCo)

JaCoCo 0.8.13 is configured to run at the `verify` phase with the following rule (from `pom.xml`):

```xml
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
```

**What this means:**

- `BUNDLE` — the rule applies to the whole project (all production classes combined), not
  class-by-class. A single class with poor coverage does not fail the build on its own; what matters
  is the aggregate.
- `LINE` — counts source lines executed at least once during the test run (as opposed to branch
  coverage or instruction coverage).
- `COVEREDRATIO` at `0.85` — at least 85% of executable lines must be hit. The build fails if
  the ratio falls below this.

**Reading the report:**

After `./mvnw verify`, open `target/site/jacoco/index.html`. The top-level table shows the bundle
total. Click through to a package, then a class, then a source file. Lines highlighted green are
covered; yellow means a branch is partially covered; red means the line was never executed.

**Investigating a coverage drop:**

1. Look at the JaCoCo report and find which file or package dropped.
2. Open the source file in the report and find the red lines.
3. Decide: is this a line that needs a test, or is it unreachable defensive code?
4. If it needs a test, write it in TDD style (red first, then green).
5. If it is genuinely defensive — a `default` branch in an exhaustive switch on a sealed type,
   or an overridden method that delegates without logic — add a note in a PR comment or leave it
   uncovered. The 85% threshold has room for these.

**Naturally low-coverage classes that do not need tests:**

- Empty record bodies — `record AccountCode(String value) {}` has a compact constructor but
  JaCoCo counts the accessor and equals/hashCode generated by the compiler.
- Spring `@Configuration` classes (added in Plan 2) — they contain only bean wiring; the Spring
  context brings them to life, not unit tests.

The gate is intentionally set at 85%, not 100%, to avoid forcing tests on code that has no
meaningful behaviour to assert.

---

## Mutation testing (PIT)

PIT 1.20.0 runs at the `verify` phase after the unit tests. It works by:

1. Taking the compiled production bytecode and applying **mutations** — small, automatic edits
   such as changing `>` to `>=`, removing a `return` statement, negating a boolean condition, or
   replacing arithmetic with a constant.
2. Rerunning the full test suite against each mutated version of the code.
3. Expecting the tests to **fail** (i.e., "kill the mutant"). If the tests all pass despite the
   mutation, the mutant survives — which means the tests were not actually verifying that
   behaviour.

**Why it matters:** JaCoCo tells you a line was executed; PIT tells you whether the tests
*care* about what the line does. A test that calls `Money.plus` but only asserts that the result is
not null will have 100% JaCoCo line coverage and zero mutation kills on the arithmetic.

**Configuration in `pom.xml`:**

```xml
<targetClasses>
  <param>co.embracejoy.accounting.keystone.domain.*</param>
  <param>co.embracejoy.accounting.keystone.application.*</param>
</targetClasses>
<mutationThreshold>60</mutationThreshold>
<failWhenNoMutations>true</failWhenNoMutations>
```

PIT mutates only `domain` and `application` packages — not `infrastructure`, which is added in
Plan 2. The threshold is 60% (at least 60% of generated mutants must be killed). The actual
kill rate with the current suite is 100%, so the threshold has room for growth without becoming
brittle.

**Reading the report:**

After `./mvnw verify`, open `target/pit-reports/<timestamp>/index.html`. The top-level table
shows the mutation score per class. Click through to a class to see individual mutants — green
rows are killed, red rows survived. A survived mutant shows you the exact bytecode change and
which line it applied to, giving you a precise pointer to a test gap.

**When to tune the threshold:**

Raise the threshold as the suite matures. Lower it temporarily when you add a new class that is
structurally hard to mutate (e.g., a value object with only accessors). Do not set the threshold
to 100% — some mutants are semantically equivalent and will never be killed.

---

## ArchUnit rules

ArchUnit 1.4.1 runs as part of the Surefire test phase. All 10 rules live in
[`HexagonalArchitectureTest.java`](../../src/test/java/co/embracejoy/accounting/keystone/architecture/HexagonalArchitectureTest.java).
The class is annotated:

```java
@AnalyzeClasses(
    packages = "co.embracejoy.accounting.keystone",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class HexagonalArchitectureTest {
```

`ImportOption.DoNotIncludeTests.class` tells ArchUnit to analyze only production bytecode. This is
important for the `NO_PUBLIC_METHOD_RETURNS_THROWABLE` rule — JUnit's `assertThrows` returns a
`Throwable`, so if test classes were included, that rule would fire on every test that calls
`assertThrows`. See the gotcha note under rule 9 below.

### The 10 rules

| # | Field name | What it enforces |
|---|---|---|
| 1 | `DOMAIN_DOES_NOT_DEPEND_ON_APPLICATION` | No class in `domain` may import anything in `application`. Domain is the innermost layer. |
| 2 | `DOMAIN_DOES_NOT_DEPEND_ON_INFRASTRUCTURE` | No class in `domain` may import anything in `infrastructure`. Infrastructure adapts to domain, not the reverse. |
| 3 | `APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE` | No class in `application` may import anything in `infrastructure`. Use-case services depend only on domain ports. |
| 4 | `DOMAIN_DOES_NOT_IMPORT_SPRING` | No class in `domain` may import `org.springframework.*`. The domain must be testable without a Spring context. |
| 5 | `DOMAIN_DOES_NOT_IMPORT_JPA` | No class in `domain` may import `jakarta.persistence.*`. JPA annotations belong only on infrastructure adapters. |
| 6 | `DOMAIN_DOES_NOT_IMPORT_JACKSON` | No class in `domain` may import `com.fasterxml.jackson.*`. Serialization is an infrastructure concern. |
| 7 | `DOMAIN_DOES_NOT_IMPORT_SLF4J` | No class in `domain` may import `org.slf4j.*`. Logging is an infrastructure concern; domain behaviour should be tested via return values, not log output. |
| 8 | `APPLICATION_DOES_NOT_IMPORT_SPRING` | No class in `application` may import `org.springframework.*`. Application services must also be instantiable without a Spring context. |
| 9 | `NO_PUBLIC_METHOD_RETURNS_THROWABLE` | No public method in the production codebase may declare a return type that is a `Throwable`. Public methods that can fail must return `Result<T, E>` instead. See [ADR-0004](../adr/0004-result-type-and-problem-details.md). |
| 10 | `CLASSES_ARE_IN_EXPECTED_TOP_LEVEL_PACKAGES` | Every class under `co.embracejoy.accounting.keystone` must reside in exactly one of the three canonical layers (`domain`, `application`, `infrastructure`) or the root package. Prevents classes from drifting into ad-hoc packages. |

### The `DoNotIncludeTests` gotcha (rule 9)

`ImportOption.DoNotIncludeTests.class` is applied to the entire `@AnalyzeClasses` annotation. This
is deliberate: `org.junit.jupiter.api.Assertions.assertThrows` has the signature
`<T extends Throwable> T assertThrows(...)`, which returns a `Throwable`. If ArchUnit scanned test
classes, `NO_PUBLIC_METHOD_RETURNS_THROWABLE` would flag `assertThrows` on every test that uses it.
By excluding test bytecode from analysis, the rule applies only to production code, where it is
meaningful.

Issue #10 will add an inline comment to `HexagonalArchitectureTest.java` to document this
directly.

### Adding a new rule

Suppose you want to enforce that no domain class may have a public setter. Add a new `@ArchTest`
field:

```java
// illustrative — not yet in the codebase
@ArchTest
static final ArchRule NO_DOMAIN_SETTERS =
    methods()
        .that()
        .arePublic()
        .and()
        .haveNameMatching("set[A-Z].*")
        .should()
        .notBeDeclaredInClassesThat()
        .resideInAPackage("..domain..");
```

Run `./mvnw test` to verify the rule compiles and passes against the current production code.
If it fails, either fix the violation or revisit whether the rule is correct.

---

## Testcontainers (Plan 2 preview)

Plan 2 introduces JPA persistence and a real Postgres 16 database. Integration tests will use:

- **`@SpringBootTest`** — starts the full Spring application context.
- **`@Testcontainers`** — manages Docker container lifecycle for the test.
- **`@ServiceConnection`** — wires the Postgres container's URL, username, and password directly
  into Spring's datasource auto-configuration with no manual property overrides.

Files that match `*IT.java` will be picked up by Failsafe (which runs at `integration-test`
phase, after Surefire). Surefire continues to run only `*Test.java` files.

This section will be expanded when Plan 2 lands. Until then, write unit tests only. Domain and
application logic should remain fully testable without a container.

---

## What not to test

**Spring `@Configuration` classes.** These contain bean-wiring boilerplate with no branching logic.
Testing them would require a Spring context and would assert only that Spring's wiring mechanism
works, not that your code is correct. JaCoCo's bundle-level threshold at 85% allows these classes
to have zero test coverage without failing the gate.

**Record accessors and record component equality.** Java generates `equals`, `hashCode`,
`toString`, and accessor methods for every record. JaCoCo counts those generated lines as
executable. Do not write tests whose sole purpose is to call `money.minorUnits()` to drive up the
number. The PIT mutation threshold is also intentionally kept below 100% partly to avoid this trap.

**`Result` itself beyond its own `ResultTest`.** The `Result` type is a general-purpose utility.
`ResultTest` covers its contract exhaustively. Tests in domain and application layers use `Result`
as a side-effect of testing real behaviour; they should not re-assert `Result`'s own mechanics.

**Plain DTO accessors.** If a class is a transparent data carrier with generated or trivially
delegating accessors and no invariants, writing tests for each accessor produces noise. Focus on
classes that enforce invariants, perform computation, or make decisions.
