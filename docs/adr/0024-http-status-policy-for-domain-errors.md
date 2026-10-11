# ADR-0024: HTTP status policy for domain errors

- **Status:** Proposed
- **Date:** 2026-10-11

Proposed for review in the PR that adds it (issue #86). Accepting it settles
issue #85 (`AlreadyReversed` returns 400, not 409). It changes no code; the
implementation follows in a separate, test-first issue.

## Context

[ADR-0004](0004-result-type-and-problem-details.md) makes `ResultMapper` the
single translation point from domain errors to RFC 9457 ProblemDetails, and
says each error maps to a stable problem `type`. [ADR-0006](0006-openapi-gates.md)
gates the OpenAPI contract. Neither says which HTTP **status** an error class
gets, so the choice was made one error at a time. The result, as the code
stands today (`infrastructure/web/ResultMapper.java` is the authority):

- Every `JournalError`, `PeriodError`, `TenantError`, `SecurityError` and
  `AccountError` variant maps to 400, 404 or 403. There is no 409 or 422
  anywhere in `src/main` or `src/test`.
- 400 is used for two different things: a request that is wrong on its own
  (an unbalanced entry) and a well-formed request that conflicts with the
  current state of a resource (reversing an entry that is already reversed,
  posting into a closed period, creating an account whose code exists).
  A client cannot tell "fix your request" from "change the state, then retry"
  without parsing the problem `type`.
- Three paths bypass the policy point. `JournalEntryController` answers
  `POST /journal-entries` failures with `ResponseEntity.badRequest()` rather
  than the status the mapper chose; `SecurityExceptionHandler` and the two
  validation/UI advices pick statuses themselves.

[docs/errors.md](../errors.md) already promises that changing the status
attached to a `type` is a breaking change, so this is a contract decision and
has to be made deliberately. Issue #85 raised the first instance
(`AlreadyReversed`); the audit under #86 found it is one of eleven.

## Decision (proposed)

Classify every error by **what it means**, then give each class one status.
The classes and their statuses are the policy; which variant belongs to which
class is read from the code (the sealed `*Error` interfaces in `domain` and
`ResultMapper`'s switch arms), not restated here.

| Class | Meaning | Status |
|---|---|---|
| Invalid input | Wrong on its own: decidable from the request and static configuration, with no stored state read. | 400 |
| Missing target | The resource the URL addresses does not exist (in the caller's tenant). | 404 |
| State conflict | Well-formed, every referenced resource exists, but the request conflicts with the current state of a resource, so the same request could succeed after that state changes. | 409 |
| Not authenticated | No valid credential. | 401 |
| Not authorised | Valid credential, but the role or tenant grants no access to the endpoint. | 403 |

Rules that make the classes decidable:

1. **A name in the body that resolves to nothing is invalid input (400), not a
   404.** 404 is reserved for the resource the URL addresses. This keeps
   `AccountNotFound` on a posting and `ParentNotFound` on an account at 400.
2. **"Could succeed after the state changes" is the test for 409.** A failure
   that no state change can cure, such as a posting currency that differs from
   an account's fixed currency, stays 400.
3. **Cross-tenant access is 404, never 403.** Tenant isolation is enforced
   by row-level security ([ADR-0016](0016-multi-tenant-row-level-isolation.md)),
   so another tenant's resource is indistinguishable from an absent one and
   the response does not reveal that it exists. 403 is for the caller's
   *role* on an endpoint (and, per
   [ADR-0017](0017-oauth2-resource-server-with-jwt-tenant-claim.md), a token
   whose tenant is unknown), which discloses nothing about any tenant's data.
4. **The ProblemDetail `type` URI never changes with this policy.** Only the
   `status` field and the HTTP status line move; clients that branch on `type`
   (as `docs/errors.md` instructs) are unaffected.
5. **Every domain error is mapped by `ResultMapper` and every controller
   uses the mapped status.** A controller that hard-codes a status for a
   mapped error (today `JournalEntryController.handle`) contradicts ADR-0004.

If accepted, the 400-to-409 move applies to the state-conflict variants of
`JournalError`, `PeriodError`, `TenantError`, `SecurityError` and
`AccountError`. Nothing moves to or from 404, 401 or 403.

## Alternatives considered

- **Keep 400 for every domain-rule failure** (option 1 of #85). Rejected, but
  it is the closest call. It costs nothing and breaks nobody, and the `type`
  URI already lets a client discriminate. It loses because status is what
  generic HTTP clients, retry logic, gateways and the `postedInvalid` style
  of status-class metrics see, and 400 tells them "do not retry unchanged"
  for requests that would succeed once a period is reopened or an account is
  reactivated. With one status for two meanings, only code that knows
  Keystone's `type` list can behave correctly.
- **422 for domain-rule failures broadly** (option 3 of #85). Rejected: the
  broad form merges the two classes this ADR separates, so it fixes nothing
  about the "retry after changing state" signal while still breaking every
  domain-rule status. The narrow form (422 only for semantically invalid
  bodies such as an unbalanced entry) would move the invalid-input class off
  400 for no behavioural gain, since Bean Validation failures in
  `ValidationExceptionHandler` are 400 and the two would then disagree about
  one kind of failure.
- **409 for every business-rule failure, including invalid input such as
  `Unbalanced`.** Rejected: 409 means the conflict is with the *state of the
  resource*. An unbalanced entry conflicts with nothing stored, and a client
  that retries on 409 would loop on a request that can never succeed.
- **403 for cross-tenant access** (an explicit "you may not see that").
  Rejected: it confirms the resource exists in another tenant, an existence
  leak the isolation model (ADR-0016) is built to prevent, and the
  application cannot even detect the case, because RLS hides the row before
  any code sees it.
- **404 for insufficient role** (hide the endpoint). Rejected: roles gate
  endpoints, not data, so 403 reveals nothing about any tenant, and the admin
  UI renders 403 as a specific "not allowed" alert
  (`UiExceptionHandler.onAccessDenied`).
- **409 only for `AlreadyReversed`** (the minimal answer to #85). Rejected:
  it fixes the instance and leaves the inconsistency; the same reasoning
  covers ten more variants, and a one-off exception is a rule nobody can
  derive.

## Consequences

**Contract.**

- Eleven error variants would change from 400 to 409; ten are produced today
  and reachable at
  `POST /journal-entries`, `POST /journal-entries/{id}/reverse`,
  `POST /periods/{yyyymm}/close` and `/reopen`, `PUT` and `DELETE`
  `/admin/users/{userSub}`, and `POST /accounts` and `PATCH /accounts/{code}`.
  The eleventh (`TenantError.Deactivated`) is mapped but never produced as a
  failure today, so it changes nothing on the wire yet.
- The generated OpenAPI document declares success responses only, so
  **Layer 3 and Layer 4 of the gate (ADR-0006) cannot see a status change**:
  the snapshot moves only where a controller's `@Operation` text names a
  status (the reverse endpoint's description cites "400
  /journal/already-reversed"), and openapi-diff reports nothing. The
  `breaking-change-approved` label will therefore not be demanded
  automatically; the PR that implements this must say it is a deliberate
  break and carry the label by reviewer judgement. The real pin is the
  web-layer tests, one per changed status, plus `docs/errors.md`, which must
  be updated in the same change (it currently states all journal errors are
  400 and omits several mapped types).
- Clients: none are known in this repository. The admin UI is unaffected:
  `layout.html` swaps any 4xx into the alert region and
  `UiResultMapper.severityFor` treats every 4xx alike. Whether external
  clients exist is not knowable from the code; ADR-0015 applies (release
  notes, no URL versioning).

**Implementation (separate issue, test first).**

- `JournalEntryController.handle` must use the mapped status before any
  `JournalError` can reach 409 on `POST /journal-entries`; without that the
  mapper change would be silently ignored there.
- Some `ResultMapper` arms are mapped but not produced anywhere in
  `src/main` today. They are classified by the same rules but untestable end
  to end until something produces them; see the PR description.

**Judgement calls for the reviewer.** Two variants are borderline and the
proposal's reading is stated, not settled: a posting to a non-leaf account
(`AccountNotALeaf`) is treated as a state conflict because its children can be
re-parented, and an account/posting currency mismatch is treated as invalid
input because the account's currency is fixed. Reversing either reading
moves one variant between 400 and 409 and changes nothing else.

**Easier:** clients and gateways can separate "fix the request" from "change
the state and retry" by status alone; 4xx metrics become meaningful by class.
**Harder:** one more rule to apply when adding an error, and a one-time break
for the eleven variants. New errors get their status by classification, not
by precedent.
