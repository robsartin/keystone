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
