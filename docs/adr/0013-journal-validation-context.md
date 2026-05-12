# ADR-0013: Domain validation that needs external data uses a context record

- **Status:** Accepted
- **Date:** 2026-05-11

## Context

`JournalEntry.of(...)` is the construction-time validator for journal
entries. The Plan-1 implementation checks invariants that depend only on
the entry's own data: postings non-empty, single currency, balanced,
no overflow. Slice 2 adds rules that need information *outside* the
entry: does each posting's account exist? Is it active? Is it a leaf?
Does its currency match? Slice 3 will add: is the entry's `YearMonth`
period open?

The domain layer rule (ADR-0002) forbids domain classes from depending
on Spring, JPA, or any port directly. We can't have `JournalEntry.of(...)`
call `AccountRepository.findByCode(...)` — that puts I/O in the domain.

## Decision

Domain validation that needs external data takes a **value-typed
context record** as a parameter. The application service does the I/O
(repository lookups) and packs the results into the context; the
domain consumes the context as plain values.

For `JournalEntry.of(...)`:

```java
public record JournalValidationContext(
        Map<AccountCode, Account> accounts) {

    public JournalValidationContext {
        Objects.requireNonNull(accounts, "accounts");
        accounts = Map.copyOf(accounts);
    }

    public static JournalValidationContext permissive() {
        return new JournalValidationContext(Map.of());
    }
}
```

Slice 3 will add a `PeriodStatus periodStatus` field to the same
record.

`JournalEntry.of(...)` gains a new overload:

```java
public static Result<JournalEntry, JournalError> of(
        LocalDate occurredOn, String description, List<Posting> postings,
        JournalValidationContext ctx);
```

The existing `of(occurredOn, description, postings)` overload remains;
it delegates to the new one with `JournalValidationContext.permissive()`
so historical tests that don't care about account validation keep
compiling. New callers should use the four-argument form.

## Consequences

- Domain stays I/O-free; ArchUnit's existing rules continue to pass.
- The service is the only place that does cross-aggregate lookups,
  which is the right place for it.
- New validation rules can be added by extending the context record;
  the domain method signature stays stable.
- Tests of `JournalEntry.of(...)` validation can mint contexts directly
  (no Spring needed).
- The `permissive()` factory is a small concession to backward
  compatibility. Once all callers are migrated to the four-argument
  form, we can deprecate it.
