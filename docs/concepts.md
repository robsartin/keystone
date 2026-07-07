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
| `tenantId` | `TenantId` | The organization the entry belongs to. Every entry is scoped to one tenant. |
| `occurredOn` | `LocalDate` | The calendar date on which the event took place. |
| `description` | `String` | A human-readable summary, e.g. `"Initial owner deposit"`. |
| `postings` | `List<Posting>` | The list of individual debit and credit lines (see below). |

There is **no currency field on the entry itself.** A single entry may span more than one
transaction currency; currency now lives on each posting, and the entry balances in a single
**base currency** (see [Postings](#postings-debits-and-credits) and
[ADR-0014](adr/0014-multi-currency-base-anchoring.md)).

keystone uses **double-entry bookkeeping**: every transaction is recorded as one or more debits
and one or more credits whose sums are exactly equal. Because both sides always balance, the books
remain self-consistent — every dollar that flows into one place came from somewhere else.

The balanced invariant is enforced at construction time. You call:

```java
Result<JournalEntry, JournalError> result =
    JournalEntry.of(tenantId, occurredOn, description, postings, context);
```

`context` is a `JournalValidationContext`: a plain data record that carries everything the domain
needs to validate the entry — the accounts each posting references, which periods are closed, and
the configured base currency. The application service does the I/O (account and period lookups,
reading config) and packs the results into the context, so the factory stays pure. See
[ADR-0013](adr/0013-journal-validation-context.md).

If the base-currency totals of the debits and credits do not match, you get back a
`JournalError.Unbalanced` — the entry never exists in memory, let alone in storage. A
`JournalEntry` object, once you have one, is always balanced.

---

## Postings: debits and credits

Each line in a journal entry is a **posting** — a record that says: account X moves by amount Y
on side Z, and that movement is worth *B* in the base currency.

```java
public record Posting(AccountCode account, Side side, Money amount, Money baseAmount) {}
```

`Side` is simply:

```java
public enum Side { DEBIT, CREDIT }
```

Both amounts are always non-negative (zero is allowed, for memo postings). Sign is conveyed by
`side`, not by a positive or negative number.

- `amount` is the movement in the **transaction currency** — the currency the event actually
  happened in. Its `currency()` must match the account's own currency.
- `baseAmount` is the *same* movement expressed in the **base currency** — the single functional
  currency the whole ledger is measured in. Its `currency()` must equal the configured base.

### Base-currency anchoring

Real ledgers have to record cross-currency transactions: a EUR bank account and a USD bank account
can both be touched by one entry. You cannot ask "do the debits equal the credits?" when the two
sides are in different currencies — €100 and $110 are not the same number.

keystone solves this the way accountants do: it picks one **base currency** (also called the
functional currency) and requires every posting to carry its value in that base. The entry then
balances in base — `Σ debit baseAmount = Σ credit baseAmount` — regardless of how many transaction
currencies are involved.

A few consequences worth knowing:

- **The base currency is configured globally**, at deploy time, via `keystone.base-currency`
  (default `USD`; environment override `KEYSTONE_BASE_CURRENCY`). There is one base per running
  instance.
- **The client supplies both amounts.** keystone does *not* look up FX rates or do any conversion
  in this slice. You compute the base-currency equivalent using whatever rate you used for the
  transaction and send both values. (Banker's rounding — `RoundingMode.HALF_EVEN` — is the
  recommended convention.)
- **Even same-currency postings carry a base amount.** When a posting is already in the base
  currency, its `baseAmount` simply equals its `amount` — but you still supply it. Explicit beats
  implicit, and the request shape stays uniform.

### The request shape

Over the REST API, a posting is a `PostingRequest`:

| Field | Meaning |
|---|---|
| `account` | The account code the posting hits. |
| `side` | `"DEBIT"` or `"CREDIT"`. |
| `minorUnits` | The transaction amount in minor units (see [Money](#money-is-integers)). |
| `currency` | The ISO 4217 code of the transaction currency, e.g. `"EUR"`. |
| `baseMinorUnits` | The same amount in the base currency's minor units. |

Note there is **no top-level `currency`** on the entry request — it moved to each posting in
Slice 6, a deliberate breaking change ([ADR-0014](adr/0014-multi-currency-base-anchoring.md)). The
base currency itself is *not* part of the request either: `baseMinorUnits` is always interpreted in
the server-configured base, so you send a number, not a currency.

### The two-column mental model

If you have never worked with debits and credits before, the simplest framing is this: imagine a
table with two columns — Debit on the left, Credit on the right. Every posting goes into one of
those columns. The rule is that the base-currency totals of both columns must match at the end of a
transaction.

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

keystone knows which side is "normal" for each account because accounts now carry a type — see
[Account codes](#account-codes) below.

### Worked example: opening a business (single currency)

You start a business by depositing $100.00 of your own cash into a company bank account. Two
accounts are affected: Cash (account code `1000`) goes up by $100, and Owner Equity (account code
`3000`) also goes up by $100. Both accounts are in USD, and the ledger's base is USD, so each
posting's base amount equals its transaction amount.

In minor units (USD cents; see [Money](#money-is-integers)):

```
DEBIT  Cash          (1000)   amount = 10000 USD   base = 10000 USD
CREDIT Owner Equity  (3000)   amount = 10000 USD   base = 10000 USD
```

Base debits: 10000. Base credits: 10000. Balanced.

In code:

```java
var postings = List.of(
    new Posting(new AccountCode("1000"), Side.DEBIT,
        new Money(10000L, USD), new Money(10000L, USD)),
    new Posting(new AccountCode("3000"), Side.CREDIT,
        new Money(10000L, USD), new Money(10000L, USD))
);
Result<JournalEntry, JournalError> result =
    JournalEntry.of(tenantId, LocalDate.of(2026, 1, 15), "Initial owner deposit", postings, context);
// result is a Result.Success<JournalEntry, ...>
```

### Worked example: buying euros (cross currency)

Now suppose the base currency is still USD, and you spend $110 of USD cash to buy €100, which lands
in a EUR-denominated cash account. The two postings are in *different* transaction currencies, but
both carry a USD base amount, and those base amounts are equal — so the entry balances.

```
DEBIT  EUR Cash  (1010)   amount = 10000 EUR   base = 11000 USD   // €100 bought at 1.10
CREDIT USD Cash  (1000)   amount = 11000 USD   base = 11000 USD   // $110 spent (already base)
```

Base debits: 11000. Base credits: 11000. Balanced — even though the transaction currencies differ.

Over the REST API:

```json
{
  "occurredOn": "2026-01-15",
  "description": "Buy EUR 100 with USD",
  "postings": [
    { "account": "1010", "side": "DEBIT",  "minorUnits": 10000, "currency": "EUR", "baseMinorUnits": 11000 },
    { "account": "1000", "side": "CREDIT", "minorUnits": 11000, "currency": "USD", "baseMinorUnits": 11000 }
  ]
}
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

Every posting carries two `Money` values — a transaction amount and a base amount (see
[Postings](#postings-debits-and-credits)) — and each is an independent minor-units-plus-currency
pair.

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

An `AccountCode` is a non-blank string that identifies a ledger account. Values typically follow a
numeric chart-of-accounts convention (`"1000"` for cash, `"3000"` for owner equity, and so on).

The `AccountCode` is the **natural key** of the `Account` aggregate — the full chart of accounts,
which shipped in Slice 2:

```java
public record Account(
    TenantId tenantId, AccountCode code, String name, AccountType type,
    Currency currency, Optional<AccountCode> parentCode, AccountStatus status) {}
```

An account carries:

- a **type** (asset, liability, equity, revenue, expense), which determines its **normal side**
  (`account.normalSide()`);
- a **currency** — each account holds a single transaction currency, and a posting's `amount`
  currency must match it;
- an optional **`parentCode`**, forming a parent/child **hierarchy** (a tree);
- a **status** (active/inactive).

Posting is **leaf-only**: you may only post to an account that has no children, and only to an
active account. These rules are enforced when the entry is constructed, via the validation context.
See [ADR-0011](adr/0011-account-hierarchy-leaf-only-posting.md).

---

## Identifiers

keystone uses **UUID version 7** (RFC 9562) as its identifier format. UUID v7 is 128 bits,
globally unique, and time-sortable: the high bits encode a millisecond timestamp, so UUIDs sort in
the same order that records were created. That property makes them useful as natural sort keys for
"last N entries" pagination without needing a separate sequence column — the same technique Stripe
and similar systems rely on.

IDs are generated **server-side** at save time, not by the caller. A constructed `JournalEntry` has
no ID; once persisted, it is paired with its storage-assigned id in a `PersistedJournalEntry`:

```java
public record JournalEntryId(UUID value) {}
public record PersistedJournalEntry(JournalEntryId id, JournalEntry entry) {}
```

The typed `JournalEntryId` wrapper keeps entry ids from being confused with other id types. See
[ADR-0010](adr/0010-journal-entry-id-wrapper.md).

---

## Errors as values

Inside keystone, operations that can fail in expected ways return `Result<T, E>` rather than
throwing exceptions:

```java
Result<JournalEntry, JournalError> result =
    JournalEntry.of(tenantId, occurredOn, description, postings, context);
```

`E` is always a **sealed interface** whose variants enumerate every failure the caller might
reasonably handle. For journal entries, those variants are:

```java
public sealed interface JournalError {
    record NoPostings()                                implements JournalError {}
    record MixedCurrencies(Set<Currency> currencies)   implements JournalError {}
    record Unbalanced(Money debits, Money credits)     implements JournalError {}
    record Overflow(Side side)                         implements JournalError {}
    record AccountNotFound(AccountCode code)           implements JournalError {}
    record AccountInactive(AccountCode code)           implements JournalError {}
    record AccountNotALeaf(AccountCode code)           implements JournalError {}
    record AccountCurrencyMismatch(AccountCode code, Currency expectedByAccount, Currency actualOnPosting)
                                                       implements JournalError {}
    record PostingInClosedPeriod(YearMonth period)     implements JournalError {}
    record BaseCurrencyMismatch(AccountCode code, Currency expectedByConfig, Currency actualOnPosting)
                                                       implements JournalError {}
}
```

- `NoPostings` — you passed an empty list of postings.
- `Unbalanced` — the base-currency debit total does not equal the base-currency credit total.
- `Overflow` — summing one side's base amounts exceeded `Long.MAX_VALUE`.
- `AccountNotFound` / `AccountInactive` / `AccountNotALeaf` — a posting references an account that
  does not exist, is deactivated, or is a non-leaf (parent) account.
- `AccountCurrencyMismatch` — a posting's transaction currency differs from the account's currency.
- `PostingInClosedPeriod` — the entry's `occurredOn` falls in an accounting period that has been
  closed.
- `BaseCurrencyMismatch` — a posting's `baseAmount` is not in the configured base currency.

`MixedCurrencies` is a historical variant. Before Slice 6, every posting in an entry had to share
one currency, and this fired otherwise. Base-currency anchoring removed that rule, so `of(...)` no
longer emits `MixedCurrencies` — but the variant is **retained** on the sealed interface (and keeps
its stable problem URI) so exhaustive `switch` handling stays valid. See
[ADR-0014](adr/0014-multi-currency-base-anchoring.md).

Because `JournalError` is sealed, the compiler can warn you if you forget to handle a variant.
True bugs (null arguments, unexpected IO failures) still throw `RuntimeException`; `Result` is
reserved for domain-level outcomes that a well-behaved caller should expect and handle.

### At the HTTP boundary

At the REST API, the `ResultMapper` translates each `JournalError` variant into an RFC 9457
`ProblemDetail` response (`application/problem+json`) with a stable `type` URI — for example
`/journal/unbalanced`, `/journal/base-currency-mismatch`, or `/journal/posting-in-closed-period`.
The URI is stable per variant so clients can branch on it programmatically.

For a deeper look at this design, see [ADR-0004](adr/0004-result-type-and-problem-details.md).

---

## What is not in the model yet

keystone is built slice by slice. The chart of accounts (Slice 2), accounting periods with
posting-date validation (Slice 3), trial-balance reporting (Slice 4), multi-tenancy with per-tenant
isolation and authentication (Slice 5), and base-anchored multi-currency (Slice 6) have all landed.

The main capability still deferred is **server-side foreign-exchange**:

- **FX rate lookup and revaluation.** Today the *client* computes each posting's base-currency
  amount (`baseMinorUnits`) from whatever rate it used; keystone stores both values but never
  performs a conversion, keeps no rate table, and does not revalue open balances as rates move.
  Server-side FX — a stored rate table, server-computed base amounts, and period-end revaluation —
  is planned for Slice 7.
  ([Slice 6 / #17](https://github.com/robsartin/keystone/issues/17) shipped the base-anchoring
  foundation this builds on.)
