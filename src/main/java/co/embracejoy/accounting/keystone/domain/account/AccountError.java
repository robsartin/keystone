package co.embracejoy.accounting.keystone.domain.account;

/** Errors raised by {@code AccountService} operations. */
public sealed interface AccountError {

  /** Trying to create an account whose code is already in use. */
  record CodeAlreadyExists(AccountCode code) implements AccountError {}

  /** The referenced account does not exist. */
  record NotFound(AccountCode code) implements AccountError {}

  /** The supplied parent code does not exist. */
  record ParentNotFound(AccountCode parentCode) implements AccountError {}

  /** Setting {@code child}'s parent to {@code proposedParent} would create a cycle. */
  record CycleWouldBeCreated(AccountCode child, AccountCode proposedParent)
      implements AccountError {}

  /**
   * Setting {@code code}'s parent to itself. Unlike {@link CycleWouldBeCreated} no change to the
   * chart can cure this, so it is invalid input rather than a state conflict (ADR-0024 rule 2).
   */
  record SelfParent(AccountCode code) implements AccountError {}

  /** Renaming to {@code code} would clash with a code already in use by an existing account. */
  record CodeInUseByPosting(AccountCode code) implements AccountError {}
}
