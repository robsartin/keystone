# Concepts

This guide explains the core ideas behind keystone for people who are integrating with it or are
simply curious about how it works. No accounting background is assumed — if you know what a bank
account is, you have enough context to follow along.

---

## Journal entries

The fundamental unit of data in keystone is the **journal entry**. Every financial event — a sale,
a payment, a refund, an adjustment — is recorded as one journal entry.

A journal entry has four pieces:

| Field | Type | Meaning |
|---|---|---|
| `occurredOn` | `LocalDate` | The calendar date on which the event took place. |
| `description` | `String` | A human-readable summary, e.g. `"Initial owner deposit"`. |
| `currency` | `Currency` | The ISO 4217 currency shared by every posting in the entry (see [Money](#money-is-integers)). |
| `postings` | `List<Posting>` | The list of individual debit and credit lines (see below). |

keystone uses **double-entry bookkeeping**: every transaction is recorded as one or more debits
and one or more credits whose sums are exactly equal. Because both sides always balance, the books
remain self-consistent — every dollar that flows into one place came from somewhere else.

The balanced invariant is enforced at construction time. You call:

```java
Result<JournalEntry, JournalError> result =
    JournalEntry.of(occurredOn, description, postings);
```

If the debits do not equal the credits, you get back a `JournalError.Unbalanced` — the entry
never exists in memory, let alone in storage. A `JournalEntry` object, once you have one, is
always balanced.

---

## Postings: debits and credits

Each line in a journal entry is a **posting** — a record that says: account X moves by amount Y
on side Z.

```java
public record Posting(AccountCode account, Side side, Money amount) {}
```

`Side` is simply:

```java
public enum Side { DEBIT, CREDIT }
```

The `amount` is always non-negative. Sign is conveyed by `side`, not by a positive or negative
number.

### The two-column mental model

If you have never worked with debits and credits before, the simplest framing is this: imagine a
table with two columns — Debit on the left, Credit on the right. Every posting goes into one of
those columns. The rule is that the totals of both columns must match at the end of a transaction.

That is the whole constraint. keystone enforces it; you just have to supply postings that satisfy
it.

### Which side increases which account?

For the accounts most integrators will use:

- To **increase** a typical asset account (cash, accounts receivable), use a **debit**.
- To **decrease** a typical asset account, use a **credit**.
- To **increase** a typical liability or equity account (owner equity, loans payable), use a
  **credit**.
- To **decrease** a typical liability or equity account, use a **debit**.

The pattern feels backwards compared to everyday banking language (where "credit" means money
arriving), but it is consistent: every transaction touches two or more accounts, and the movement
on each account follows the rule above.

> **Note:** Account-type semantics — which accounts are assets, which are liabilities, which are
> equity — are not modelled in the keystone phase. `AccountCode` is just a typed string today.
> The full chart of accounts with types and normal sides arrives in
> [Slice 2 / #13](https://github.com/robsartin/keystone/issues/13).

### Worked example: opening a business

You start a business by depositing $100.00 of your own cash into a company bank account. Two
accounts are affected: Cash (account code `1000`) goes up by $100, and Owner Equity (account code
`3000`) also goes up by $100.

In minor units (USD cents; see [Money](#money-is-integers)):

```
DEBIT  Cash          (1000)    10000    // 10000 cents = $100.00
CREDIT Owner Equity  (3000)    10000
```

Total debits: 10000. Total credits: 10000. Balanced.

In code:

```java
var postings = List.of(
    new Posting(new AccountCode("1000"), Side.DEBIT,  new Money(10000L, USD)),
    new Posting(new AccountCode("3000"), Side.CREDIT, new Money(10000L, USD))
);
Result<JournalEntry, JournalError> result =
    JournalEntry.of(LocalDate.of(2026, 1, 15), "Initial owner deposit", postings);
// result is a Result.Success<JournalEntry, ...>
```

---

## Money is integers

```java
public record Money(long minorUnits, Currency currency) {}
```

Monetary amounts are stored as whole numbers of the **smallest unit** the currency supports. The
currency is an ISO 4217 `java.util.Currency`; its `getDefaultFractionDigits()` tells you the
scale:

| Currency | Minor unit | Example: `minorUnits = 1234` |
|---|---|---|
| USD (US dollar) | 1 cent = $0.01 | 1234 → $12.34 |
| JPY (Japanese yen) | 1 yen (no subdivisions) | 1234 → ¥1234 |
| BHD (Bahraini dinar) | 1 fil = 0.001 BHD | 1234 → 1.234 BHD |

### Why not `double` or `BigDecimal`?

`double` uses binary floating-point, which cannot represent many decimal fractions exactly.
`0.1 + 0.2` in binary floating-point is `0.30000000000000004`, not `0.3`. That kind of error
accumulates and is completely unacceptable in financial calculations.

`BigDecimal` is mathematically correct but easy to misuse: developers frequently forget to set the
scale or the rounding mode, and the resulting silent errors are hard to detect in tests.

Integer arithmetic avoids both problems. The values are exact, and overflow is detected
immediately (`Money.plus` and `Money.minus` use `Math.addExact` / `Math.subtractExact` and return
`MoneyError.Overflow` instead of wrapping silently). Stripe, Shopify, and most modern payment
systems use the same representation.

### Display formatting

Converting `10000` (USD) to `"$100.00"` is display formatting, not domain logic. That conversion
happens at the API boundary, never inside the domain. The raw integer is what keystone stores and
what you send in API requests.

For a deeper look at this choice, see [ADR-0003](adr/0003-money-as-integer-minor-units.md).

---

## Account codes

```java
public record AccountCode(String value) {}
```

An `AccountCode` is a non-blank string that identifies a ledger account. In the keystone phase it
is a typed wrapper — the code enforces that the value is present and non-blank, but it carries no
further semantics (no account type, no hierarchy, no normal side).

Typical values follow a numeric chart-of-accounts convention (`"1000"` for cash, `"3000"` for
owner equity, and so on), but keystone does not require any particular format at this stage.

The full `Account` aggregate — with type, normal side, parent/child hierarchy, and validation
against a chart of accounts — arrives in
[Slice 2 / #13](https://github.com/robsartin/keystone/issues/13).

---

## Identifiers

keystone uses **UUID version 7** (RFC 9562) as its identifier format. UUID v7 is 128 bits,
globally unique, and time-sortable: the high bits encode a millisecond timestamp, so UUIDs sort in
the same order that records were created. That property makes them useful as natural sort keys for
"last N entries" pagination without needing a separate sequence column — the same technique Stripe
and similar systems rely on.

IDs are generated **server-side** at save time, not by the caller. The `PersistedJournalEntry`
wrapper that carries the ID (along with audit timestamps) lands in Plan 2, alongside the
persistence and HTTP layers. ADR-0010, which documents the UUID v7 choice in detail, also lands in
Plan 2.

---

## Errors as values

Inside keystone, operations that can fail in expected ways return `Result<T, E>` rather than
throwing exceptions:

```java
Result<JournalEntry, JournalError> result =
    JournalEntry.of(occurredOn, description, postings);
```

`E` is always a **sealed interface** whose variants enumerate every failure the caller might
reasonably handle. For journal entries, those variants are:

```java
public sealed interface JournalError {
    record NoPostings()                              implements JournalError {}
    record MixedCurrencies(Set<Currency> currencies) implements JournalError {}
    record Unbalanced(Money debits, Money credits)   implements JournalError {}
}
```

`NoPostings` — you passed an empty list of postings.  
`MixedCurrencies` — postings referenced more than one currency in a single entry.  
`Unbalanced` — the debit total does not equal the credit total.

Because `JournalError` is sealed, the compiler can warn you if you forget to handle a variant.
True bugs (null arguments, unexpected IO failures) still throw `RuntimeException`; `Result` is
reserved for domain-level outcomes that a well-behaved caller should expect and handle.

### At the HTTP boundary (Plan 2)

When the REST API ships in Plan 2, each `Result.Failure` variant will be translated into an
RFC 9457 `ProblemDetail` response with a stable `type` URI. The full error reference catalog,
including every URI and the fields each problem detail carries, is tracked in
[#23](https://github.com/robsartin/keystone/issues/23) and will be published once Plan 2 merges.

For a deeper look at this design, see [ADR-0004](adr/0004-result-type-and-problem-details.md).

---

## What is not in the model yet

keystone is built slice by slice. The following features are explicitly deferred — do not expect
them to be present or enforced today:

- **Chart of accounts** — account types (asset, liability, equity, revenue, expense), normal
  sides, and parent/child hierarchy. Currently `AccountCode` is just a string.
  ([Slice 2 / #13](https://github.com/robsartin/keystone/issues/13))

- **Accounting periods and posting-date validation** — there is no concept of an open or closed
  period. Any `occurredOn` date is accepted.
  ([Slice 3 / #14](https://github.com/robsartin/keystone/issues/14))

- **Trial balance and financial reports** — no aggregation, no balance queries, no report
  generation yet.
  ([Slice 4 / #15](https://github.com/robsartin/keystone/issues/15))

- **Authentication and multi-tenancy** — all data is currently single-tenant; there is no
  authentication layer.
  ([Slice 5 / #16](https://github.com/robsartin/keystone/issues/16))

- **Multi-currency entries with FX revaluation** — all postings in a single journal entry must
  share one currency. Cross-currency transactions and foreign-exchange revaluation are a future
  capability.
  ([Slice 6 / #17](https://github.com/robsartin/keystone/issues/17))
