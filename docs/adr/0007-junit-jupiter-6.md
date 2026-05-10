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
