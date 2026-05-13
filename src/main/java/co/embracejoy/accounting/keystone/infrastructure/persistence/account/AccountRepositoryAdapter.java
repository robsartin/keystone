package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class AccountRepositoryAdapter implements AccountRepository {

  private final JpaAccountRepository jpa;

  public AccountRepositoryAdapter(JpaAccountRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Result<Account, AccountError> save(Account account) {
    if (jpa.existsById(account.code().value())) {
      return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
    }
    try {
      AccountEntity saved = jpa.save(AccountEntityMapper.toEntity(account));
      return Result.success(AccountEntityMapper.toDomain(saved));
    } catch (DataIntegrityViolationException ex) {
      // Race: a parallel insert created the same code between existsById check and save.
      return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
    }
  }

  @Override
  public Result<Account, AccountError> update(Account account) {
    if (!jpa.existsById(account.code().value())) {
      return Result.failure(new AccountError.NotFound(account.code()));
    }
    AccountEntity entity = jpa.findById(account.code().value()).orElseThrow();
    entity.setName(account.name());
    entity.setParentCode(account.parentCode().map(AccountCode::value).orElse(null));
    entity.setActive(account.isActive());
    return Result.success(AccountEntityMapper.toDomain(jpa.save(entity)));
  }

  @Override
  public Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode) {
    if (jpa.existsById(newCode.value())) {
      return Result.failure(new AccountError.CodeInUseByPosting(newCode));
    }
    if (!jpa.existsById(existing.value())) {
      return Result.failure(new AccountError.NotFound(existing));
    }
    jpa.renameCode(existing.value(), newCode.value());
    return Result.success(
        AccountEntityMapper.toDomain(jpa.findById(newCode.value()).orElseThrow()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Account> findByCode(AccountCode code) {
    return jpa.findById(code.value()).map(AccountEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Account> findAll() {
    return jpa.findAll().stream().map(AccountEntityMapper::toDomain).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<AccountCode, Account> findByCodeIn(Set<AccountCode> codes) {
    List<String> ids = codes.stream().map(AccountCode::value).toList();
    Map<AccountCode, Account> out = new LinkedHashMap<>();
    for (AccountEntity e : jpa.findAllByCodeIn(ids)) {
      Account a = AccountEntityMapper.toDomain(e);
      out.put(a.code(), a);
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasChildren(AccountCode code) {
    return jpa.existsByParentCode(code.value());
  }
}
