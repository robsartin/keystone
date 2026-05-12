package co.embracejoy.accounting.keystone.domain.account;

import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Persistence port for {@link Account} aggregates. */
public interface AccountRepository {

  /**
   * Persist a new account.
   *
   * @return {@code Success(saved)} or {@code Failure(CodeAlreadyExists)} when the natural key
   *     clashes
   */
  Result<Account, AccountError> save(Account account);

  /**
   * Persist an update to an existing account. Distinct from {@code save(...)} so the adapter can
   * detect "does not exist" vs "duplicate" — different SQL paths, different error returns.
   *
   * @return {@code Success(updated)} or {@code Failure(NotFound)} when the account doesn't exist
   */
  Result<Account, AccountError> update(Account account);

  /** Find an account by its primary key. */
  Optional<Account> findByCode(AccountCode code);

  /** All accounts in code order. */
  List<Account> findAll();

  /**
   * Batch lookup for {@code JournalEntry.of(...)} validation. Returns only matched codes — missing
   * codes simply don't appear in the map.
   */
  Map<AccountCode, Account> findByCodeIn(Set<AccountCode> codes);

  /** True if the account has at least one child (used by the leaf-only-posting rule). */
  boolean hasChildren(AccountCode code);

  /**
   * Atomic rename: change the natural key from {@code existing} to {@code newCode}. The adapter
   * issues a single SQL UPDATE; FK cascades on dependents.
   *
   * @return {@code Success(renamed)} or {@code Failure(NotFound)} when existing is absent, or
   *     {@code Failure(CodeInUseByPosting)} when newCode is already taken.
   */
  Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode);
}
