package co.embracejoy.accounting.keystone.application.account;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Use-case service for chart-of-accounts management. */
public final class AccountService {

  private final AccountRepository repository;

  public AccountService(AccountRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /** Create a new account. Validates parent existence before persisting. */
  public Result<Account, AccountError> create(
      AccountCode code,
      String name,
      AccountType type,
      Currency currency,
      Optional<AccountCode> parentCode) {
    if (parentCode.isPresent() && repository.findByCode(parentCode.get()).isEmpty()) {
      return Result.failure(new AccountError.ParentNotFound(parentCode.get()));
    }
    return repository.save(new Account(code, name, type, currency, parentCode, true));
  }

  /** Rename an account to a new code. Delegates to the repository's atomic rename primitive. */
  public Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode) {
    return repository.rename(existing, newCode);
  }

  /** Re-parent an account. Validates the new parent exists and detects cycles. */
  public Result<Account, AccountError> setParent(
      AccountCode code, Optional<AccountCode> newParentCode) {
    Optional<Account> opt = repository.findByCode(code);
    if (opt.isEmpty()) {
      return Result.failure(new AccountError.NotFound(code));
    }
    if (newParentCode.isPresent()) {
      if (repository.findByCode(newParentCode.get()).isEmpty()) {
        return Result.failure(new AccountError.ParentNotFound(newParentCode.get()));
      }
      if (wouldCreateCycle(code, newParentCode.get())) {
        return Result.failure(new AccountError.CycleWouldBeCreated(code, newParentCode.get()));
      }
    }
    Account a = opt.get();
    return repository.update(
        new Account(a.code(), a.name(), a.type(), a.currency(), newParentCode, a.active()));
  }

  /** Deactivate an account. Idempotent: already-inactive accounts return Success. */
  public Result<Account, AccountError> deactivate(AccountCode code) {
    return setActive(code, false);
  }

  /** Reactivate an account. Idempotent: already-active accounts return Success. */
  public Result<Account, AccountError> reactivate(AccountCode code) {
    return setActive(code, true);
  }

  /** Look up a single account by code. */
  public Optional<Account> findByCode(AccountCode code) {
    return repository.findByCode(code);
  }

  /** Return all accounts. */
  public List<Account> findAll() {
    return repository.findAll();
  }

  private Result<Account, AccountError> setActive(AccountCode code, boolean active) {
    Optional<Account> opt = repository.findByCode(code);
    if (opt.isEmpty()) {
      return Result.failure(new AccountError.NotFound(code));
    }
    Account a = opt.get();
    if (a.active() == active) {
      return Result.success(a); // idempotent
    }
    return repository.update(
        new Account(a.code(), a.name(), a.type(), a.currency(), a.parentCode(), active));
  }

  private boolean wouldCreateCycle(AccountCode child, AccountCode proposedParent) {
    Set<AccountCode> visited = new HashSet<>();
    AccountCode current = proposedParent;
    while (current != null) {
      if (current.equals(child)) {
        return true;
      }
      if (!visited.add(current)) {
        return false; // guard against corrupt tree
      }
      Optional<Account> next = repository.findByCode(current);
      current = next.flatMap(Account::parentCode).orElse(null);
    }
    return false;
  }
}
