package co.embracejoy.accounting.keystone.application.account;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountRepository;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AccountService")
class AccountServiceTest {

  private FakeAccountRepository repo;
  private AccountService service;
  private static final Currency USD = Currency.getInstance("USD");
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));

  @BeforeEach
  void setup() {
    repo = new FakeAccountRepository();
    service = new AccountService(repo);
  }

  @Test
  @DisplayName("create persists when code is fresh")
  void shouldCreateWhenCodeFresh() {
    Result<Account, AccountError> r =
        service.create(
            TENANT, new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(repo.byCode).containsKey(new AccountCode("1000"));
  }

  @Test
  @DisplayName("create returns CodeAlreadyExists on duplicate")
  void shouldReturnDuplicateWhenCodeExists() {
    service.create(
        TENANT, new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    Result<Account, AccountError> r =
        service.create(
            TENANT, new AccountCode("1000"), "Cash 2", AccountType.ASSET, USD, Optional.empty());
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.CodeAlreadyExists.class);
  }

  @Test
  @DisplayName("create returns ParentNotFound when parent absent")
  void shouldReturnParentNotFoundWhenParentMissing() {
    Result<Account, AccountError> r =
        service.create(
            TENANT,
            new AccountCode("1000"),
            "Cash",
            AccountType.ASSET,
            USD,
            Optional.of(new AccountCode("ghost")));
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.ParentNotFound.class);
  }

  @Test
  @DisplayName("setParent returns CycleWouldBeCreated when target is a descendant")
  void shouldDetectCycleWhenReparenting() {
    service.create(
        TENANT, new AccountCode("1"), "Assets", AccountType.ASSET, USD, Optional.empty());
    service.create(
        TENANT,
        new AccountCode("10"),
        "Current",
        AccountType.ASSET,
        USD,
        Optional.of(new AccountCode("1")));
    service.create(
        TENANT,
        new AccountCode("100"),
        "Cash",
        AccountType.ASSET,
        USD,
        Optional.of(new AccountCode("10")));

    // Re-parent "1" under "100" → cycle (1 → 10 → 100 → 1).
    Result<Account, AccountError> r =
        service.setParent(new AccountCode("1"), Optional.of(new AccountCode("100")));
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.CycleWouldBeCreated.class);
  }

  @Test
  @DisplayName("rename to an unused code succeeds")
  void shouldRenameWhenNewCodeFree() {
    service.create(
        TENANT, new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    Result<Account, AccountError> r =
        service.rename(new AccountCode("1000"), new AccountCode("1001"));
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(repo.byCode).doesNotContainKey(new AccountCode("1000"));
    assertThat(repo.byCode).containsKey(new AccountCode("1001"));
  }

  @Test
  @DisplayName("rename to an in-use code returns CodeInUseByPosting")
  void shouldReturnCodeInUseWhenRenameClashes() {
    service.create(
        TENANT, new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    service.create(
        TENANT, new AccountCode("1001"), "Petty Cash", AccountType.ASSET, USD, Optional.empty());

    Result<Account, AccountError> r =
        service.rename(new AccountCode("1000"), new AccountCode("1001"));
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.CodeInUseByPosting.class);
  }

  @Test
  @DisplayName("deactivate marks active=false and is idempotent")
  void shouldDeactivateIdempotently() {
    service.create(
        TENANT, new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    service.deactivate(new AccountCode("1000"));
    Result<Account, AccountError> r = service.deactivate(new AccountCode("1000"));
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(repo.byCode.get(new AccountCode("1000")).isActive()).isFalse();
  }

  @Test
  @DisplayName("reactivate flips active back to true")
  void shouldReactivate() {
    service.create(
        TENANT, new AccountCode("1000"), "Cash", AccountType.ASSET, USD, Optional.empty());
    service.deactivate(new AccountCode("1000"));
    Result<Account, AccountError> r = service.reactivate(new AccountCode("1000"));
    assertThat(r).isInstanceOf(Result.Success.class);
    assertThat(repo.byCode.get(new AccountCode("1000")).isActive()).isTrue();
  }

  // ---- fake ----

  private static final class FakeAccountRepository implements AccountRepository {
    final Map<AccountCode, Account> byCode = new HashMap<>();
    final Set<AccountCode> parents = new HashSet<>();

    @Override
    public Result<Account, AccountError> save(Account account) {
      if (byCode.containsKey(account.code())) {
        return Result.failure(new AccountError.CodeAlreadyExists(account.code()));
      }
      byCode.put(account.code(), account);
      account.parentCode().ifPresent(parents::add);
      return Result.success(account);
    }

    @Override
    public Result<Account, AccountError> update(Account account) {
      if (!byCode.containsKey(account.code())) {
        return Result.failure(new AccountError.NotFound(account.code()));
      }
      byCode.put(account.code(), account);
      parents.clear();
      for (Account a : byCode.values()) {
        a.parentCode().ifPresent(parents::add);
      }
      return Result.success(account);
    }

    @Override
    public Result<Account, AccountError> rename(AccountCode existing, AccountCode newCode) {
      if (byCode.containsKey(newCode)) {
        return Result.failure(new AccountError.CodeInUseByPosting(newCode));
      }
      Account a = byCode.remove(existing);
      if (a == null) {
        return Result.failure(new AccountError.NotFound(existing));
      }
      Account renamed =
          new Account(
              a.tenantId(), newCode, a.name(), a.type(), a.currency(), a.parentCode(), a.status());
      byCode.put(newCode, renamed);
      parents.clear();
      for (Account acc : byCode.values()) {
        acc.parentCode().ifPresent(parents::add);
      }
      return Result.success(renamed);
    }

    @Override
    public Optional<Account> findByCode(AccountCode code) {
      return Optional.ofNullable(byCode.get(code));
    }

    @Override
    public List<Account> findAll() {
      return new ArrayList<>(byCode.values());
    }

    @Override
    public Map<AccountCode, Account> findByCodeIn(Set<AccountCode> codes) {
      Map<AccountCode, Account> out = new HashMap<>();
      for (AccountCode c : codes) {
        Account a = byCode.get(c);
        if (a != null) {
          out.put(c, a);
        }
      }
      return out;
    }

    @Override
    public boolean hasChildren(AccountCode code) {
      return parents.contains(code);
    }
  }
}
