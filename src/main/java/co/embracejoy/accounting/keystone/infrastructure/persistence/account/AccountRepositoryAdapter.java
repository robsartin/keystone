package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.RlsTransactionInterceptor;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class AccountRepositoryAdapter implements AccountRepository {

  private final JpaAccountRepository jpa;
  private final TenantContext tenantContext;
  private final RlsTransactionInterceptor rlsInterceptor;

  public AccountRepositoryAdapter(
      JpaAccountRepository jpa,
      TenantContext tenantContext,
      RlsTransactionInterceptor rlsInterceptor) {
    this.jpa = jpa;
    this.tenantContext = tenantContext;
    this.rlsInterceptor = rlsInterceptor;
  }

  @Override
  public Result<Account, AccountError> save(Account account) {
    TenantId tid = tenantContext.require();
    validateTenantMatch(tid, account.tenantId());
    rlsInterceptor.applyToCurrentTransaction();
    if (jpa.existsByTenantIdAndCode(tid.value(), account.code().value())) {
      return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
    }
    try {
      AccountEntity saved = jpa.save(AccountEntityMapper.toEntity(account));
      return Result.success(AccountEntityMapper.toDomain(saved));
    } catch (DataIntegrityViolationException ex) {
      return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
    }
  }

  @Override
  public Result<Account, AccountError> update(Account account) {
    TenantId tid = tenantContext.require();
    validateTenantMatch(tid, account.tenantId());
    rlsInterceptor.applyToCurrentTransaction();
    UUID tenantUuid = tid.value();
    if (!jpa.existsByTenantIdAndCode(tenantUuid, account.code().value())) {
      return Result.failure(new AccountError.NotFound(account.code()));
    }
    AccountEntity entity =
        jpa.findByTenantIdAndCode(tenantUuid, account.code().value()).orElseThrow();
    entity.setName(account.name());
    entity.setParentCode(account.parentCode().map(AccountCode::value).orElse(null));
    entity.setActive(account.isActive());
    return Result.success(AccountEntityMapper.toDomain(jpa.save(entity)));
  }

  @Override
  public Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode) {
    TenantId tid = tenantContext.require();
    rlsInterceptor.applyToCurrentTransaction();
    UUID tenantUuid = tid.value();
    if (jpa.existsByTenantIdAndCode(tenantUuid, newCode.value())) {
      return Result.failure(new AccountError.CodeInUseByPosting(newCode));
    }
    if (!jpa.existsByTenantIdAndCode(tenantUuid, existing.value())) {
      return Result.failure(new AccountError.NotFound(existing));
    }
    jpa.renameCodeForTenant(tenantUuid, existing.value(), newCode.value());
    return Result.success(
        AccountEntityMapper.toDomain(
            jpa.findByTenantIdAndCode(tenantUuid, newCode.value()).orElseThrow()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Account> findByCode(AccountCode code) {
    TenantId tid = tenantContext.require();
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.findByTenantIdAndCode(tid.value(), code.value()).map(AccountEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Account> findAll() {
    TenantId tid = tenantContext.require();
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.findAllByTenantIdOrderByCode(tid.value()).stream()
        .map(AccountEntityMapper::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<AccountCode, Account> findByCodeIn(Set<AccountCode> codes) {
    TenantId tid = tenantContext.require();
    rlsInterceptor.applyToCurrentTransaction();
    List<String> ids = codes.stream().map(AccountCode::value).toList();
    Map<AccountCode, Account> out = new LinkedHashMap<>();
    for (AccountEntity e : jpa.findAllByTenantIdAndCodeIn(tid.value(), ids)) {
      Account a = AccountEntityMapper.toDomain(e);
      out.put(a.code(), a);
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasChildren(AccountCode code) {
    TenantId tid = tenantContext.require();
    rlsInterceptor.applyToCurrentTransaction();
    return jpa.existsByTenantIdAndParentCode(tid.value(), code.value());
  }

  private void validateTenantMatch(TenantId contextTid, TenantId accountTid) {
    if (!contextTid.equals(accountTid)) {
      throw new IllegalStateException(
          "tenant mismatch — account is " + accountTid + ", context is " + contextTid);
    }
  }
}
