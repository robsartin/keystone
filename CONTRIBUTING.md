# Contributing to Keystone

This guide covers everything you need to contribute: setting up your environment,
running the build, following the PR workflow, and writing code that meets the
project's quality bar. Read it top to bottom the first time; use it as a reference
after that.

For AI-pairing conventions, see [CLAUDE.md](CLAUDE.md).

---

## Table of contents

1. [Prerequisites](#prerequisites)
2. [Building the project](#building-the-project)
3. [PR workflow](#pr-workflow)
4. [TDD discipline](#tdd-discipline)
5. [Commit conventions](#commit-conventions)
6. [Further reading](#further-reading)
7. [Questions and bug reports](#questions-and-bug-reports)

---

## Prerequisites

### JDK 25

Keystone targets Java 25. The recommended way to install and manage JDKs is
[SDKMAN](https://sdkman.io/):

```bash
curl -s "https://get.sdkman.io" | bash
sdk install java 25.0.3-tem
sdk use java 25.0.3-tem
```

Verify with:

```bash
java -version
```

The output should show `25`.

### Maven

You do not need to install Maven separately. The repository includes the Maven
wrapper (`./mvnw`). The first invocation downloads the correct Maven version
(3.9.x) automatically.

### Docker

Docker is not required for Plan-1 work. Once Plan 2 lands (Testcontainers +
Postgres integration tests), you will need Docker Desktop or a compatible
container runtime. Install it now if you want to be ready:
<https://docs.docker.com/get-docker/>

### GitHub CLI

Use `gh` for the PR workflow described below:

```bash
brew install gh   # macOS
gh auth login
```

---

## Building the project

### Single verify mode (Plan 1)

Right now there is one build command:

```bash
./mvnw -B verify
```

This runs the full local gate in order:

1. **Spotless** — formats Java using Google Java Format; fails if files are not
   already formatted (run `./mvnw spotless:apply` to fix before committing)
2. **Checkstyle** — enforces style rules (file ≤ 750 lines, method ≤ 30 lines,
   no star imports, braces required)
3. **JUnit Jupiter 6 unit tests** — all tests under `src/test/`
4. **JaCoCo** — line coverage must be ≥ 85%
5. **PIT mutation** — mutation score must be ≥ 60% on `domain` and `application`
   packages
6. **ArchUnit** — 10 hexagonal-architecture rules; see
   [ADR-0002](docs/adr/0002-hexagonal-architecture.md)

If any stage fails, the build fails. Fix the failure before pushing.

For fast iteration during development you can run tests alone:

```bash
./mvnw test
```

But always run the full `./mvnw -B verify` before pushing. Do not open a PR
against a red build.

### Plan 2 build modes (coming)

Plan 2 will add Maven profiles `-Pmutation`, `-Popenapi-gate`, and
`-Popenapi-update`. This file will be updated when those profiles land.

---

## PR workflow

### 1. Open an issue first

Every change begins with a GitHub issue. If one does not exist for the work you
want to do, create it before writing code. The issue is the unit of
conversation; the PR is just the delivery vehicle.

### 2. Create a branch from main

Branch names follow the pattern `<issue-number>-<short-slug>`:

```bash
git checkout main
git pull
git checkout -b 42-add-account-entity
```

Never commit directly to `main`.

### 3. Work in small, atomic commits

Each commit should compile, pass tests, and represent a single logical step.
See [Commit conventions](#commit-conventions) below.

### 4. Push and open a PR

```bash
git push -u origin 42-add-account-entity
gh pr create --title "feat(domain): AccountEntity value object" \
             --body "$(cat <<'EOF'
Short description of what and why.

Closes #42
EOF
)"
```

The `Closes #42` line in the body links the PR to the issue and closes it on
merge.

### 5. Merge

After review, merge using **squash-merge** to keep the main-branch history
linear and readable.

---

## TDD discipline

Keystone is built test-first. The cycle is non-negotiable:

```
RED -> GREEN -> REFACTOR -> COMMIT
```

**RED** — write a failing test that describes the behaviour you are about to
implement. Run it and verify it fails for the right reason (a compile error or
a meaningful assertion failure, not an accidental one).

**GREEN** — write the minimum production code that makes the test pass. Resist
the urge to over-engineer at this stage.

**REFACTOR** — clean up without changing observable behaviour. The tests stay
green throughout.

**COMMIT** — commit the test and the implementation together. The commit message
describes what the test+impl pair accomplishes (see below).

### Test naming

- Method name: `should<Expected>When<Condition>` (camel-case, no underscores)
- Annotation: `@DisplayName` with a human-readable sentence

Example:

```java
@Test
@DisplayName("should reject a debit posting with a negative amount")
void shouldRejectDebitPostingWhenAmountIsNegative() { ... }
```

### Quality gates

All three gates run as part of `./mvnw -B verify`:

| Gate | Threshold |
|------|-----------|
| JaCoCo line coverage | ≥ 85% |
| PIT mutation score (`domain`, `application`) | ≥ 60% |
| ArchUnit hexagonal rules | all 10 rules pass |

If your change causes a gate to drop, fix it before pushing.

---

## Commit conventions

### Subject line

```
<type>(<scope>): <subject>
```

- **type** — one of `feat`, `fix`, `test`, `docs`, `build`, `refactor`, `chore`
- **scope** — the affected package or area: `domain`, `application`, `web`,
  `build`, `adr`, etc.
- **subject** — imperative mood, lowercase, ≤ 72 characters total for the line

### Body

Wrap at ~72 characters. Explain *why*, not just *what*, when the diff alone is
not self-documenting.

### Trailers

- `Closes #<n>` — when the commit closes an issue
- `Co-Authored-By: <name> <email>` — when AI-paired; use
  `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`

### Examples from this repository

```
feat(application): PostJournalEntryService
```

```
build: enforce PIT mutation coverage ≥60% on domain + application
```

```
test(architecture): ArchUnit hexagonal layering rules
```

```
docs(adr): 0002 hexagonal architecture enforced by ArchUnit
```

---

## Further reading

- **ADRs** — [`docs/adr/`](docs/adr/) contains the architecture decision records
  that explain key design choices. An ADR index (`docs/adr/README.md`) is coming
  in issue #9; until then, browse the directory directly. Start with
  [ADR-0002](docs/adr/0002-hexagonal-architecture.md) (hexagonal architecture)
  and [ADR-0004](docs/adr/0004-result-type-and-problem-details.md) (Result type).
- **Foundation design spec** —
  [`docs/superpowers/specs/2026-05-09-keystone-foundation-design.md`](docs/superpowers/specs/2026-05-09-keystone-foundation-design.md)
  describes the full three-plan roadmap and the rationale for the architectural
  choices.
- **AI-pairing conventions** — [`CLAUDE.md`](CLAUDE.md) covers the quick-reference
  commands, architecture rules, key conventions, and code-style expectations that
  apply when working alongside an AI assistant.

---

## Questions and bug reports

Open a GitHub issue at <https://github.com/robsartin/keystone/issues>.

- Use the `bug` label for defects.
- Use the `question` label for anything unclear about the codebase or contribution
  process.

Please search existing issues before opening a new one.
