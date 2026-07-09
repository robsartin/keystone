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

## Enforcement

`MoneyIsIntegerArchTest` asserts (via 5 ArchUnit rules) that no field in
`..domain..` has a raw type of `double`, `float`, `Double`, `Float`, or
`BigDecimal`. Domain classes may only hold integer money via `long`; the
infrastructure boundary is free to convert on I/O (e.g. Postgres numeric
columns via JPA), but the domain itself never sees floating-point money.
