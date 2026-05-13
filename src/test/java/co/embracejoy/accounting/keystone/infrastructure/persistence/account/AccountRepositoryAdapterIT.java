package co.embracejoy.accounting.keystone.infrastructure.persistence.account;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.KeystoneApplication;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = KeystoneApplication.class)
@Testcontainers
@Transactional
@DisplayName("AccountRepositoryAdapter (integration)")
class AccountRepositoryAdapterIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("keystone")
          .withUsername("test")
          .withPassword("test");

  @Autowired AccountRepositoryAdapter repository;

  private static final Currency USD = Currency.getInstance("USD");
  // Use codes outside the V4 seed range (1000, 1100, 3000, 4000) to avoid conflicts.
  private static final AccountCode ASSETS = new AccountCode("2");
  private static final AccountCode CASH = new AccountCode("2000");
  private static final AccountCode RECEIVABLES = new AccountCode("2100");

  private static Account asset(AccountCode code, String name, Optional<AccountCode> parent) {
    return new Account(code, name, AccountType.ASSET, USD, parent, AccountStatus.ACTIVE);
  }

  @Test
  @DisplayName("save persists a new account and findByCode reads it back")
  void shouldRoundTripWhenSavingThenReading() {
    Account a = asset(CASH, "Cash", Optional.empty());
    Result<Account, AccountError> r = repository.save(a);

    assertThat(r).isInstanceOf(Result.Success.class);
    Optional<Account> found = repository.findByCode(CASH);
    assertThat(found).contains(a);
  }

  @Test
  @DisplayName("save returns CodeAlreadyExists on duplicate key")
  void shouldReturnCodeAlreadyExistsWhenDuplicate() {
    repository.save(asset(CASH, "Cash", Optional.empty()));

    Result<Account, AccountError> r = repository.save(asset(CASH, "Cash Again", Optional.empty()));

    assertThat(r).isInstanceOf(Result.Failure.class);
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.CodeAlreadyExists.class);
  }

  @Test
  @DisplayName("update returns NotFound when account doesn't exist")
  void shouldReturnNotFoundWhenUpdatingMissing() {
    Result<Account, AccountError> r =
        repository.update(asset(new AccountCode("9999"), "Ghost", Optional.empty()));
    assertThat(r).isInstanceOf(Result.Failure.class);
    assertThat(((Result.Failure<Account, AccountError>) r).error())
        .isInstanceOf(AccountError.NotFound.class);
  }

  @Test
  @DisplayName("findByCodeIn returns only matched codes")
  void shouldReturnOnlyMatchedCodesWhenBatchLooking() {
    repository.save(asset(CASH, "Cash", Optional.empty()));
    repository.save(asset(RECEIVABLES, "Receivables", Optional.empty()));

    Map<AccountCode, Account> found =
        repository.findByCodeIn(Set.of(CASH, RECEIVABLES, new AccountCode("missing")));

    assertThat(found).hasSize(2).containsKeys(CASH, RECEIVABLES);
  }

  @Test
  @DisplayName("hasChildren is true when at least one child exists")
  void shouldReturnTrueWhenAccountHasChildren() {
    repository.save(asset(ASSETS, "Assets", Optional.empty()));
    repository.save(asset(CASH, "Cash", Optional.of(ASSETS)));

    assertThat(repository.hasChildren(ASSETS)).isTrue();
    assertThat(repository.hasChildren(CASH)).isFalse();
  }

  @Test
  @DisplayName("rename succeeds and old code is gone, new code has the entity")
  void shouldRenameWhenCodeAvailable() {
    AccountCode oldCode = new AccountCode("9001");
    AccountCode newCode = new AccountCode("9002");
    repository.save(asset(oldCode, "Rename Me", Optional.empty()));

    Result<Account, AccountError> r = repository.rename(oldCode, newCode);

    assertThat(r).isInstanceOf(Result.Success.class);
    Account renamed = ((Result.Success<Account, AccountError>) r).value();
    assertThat(renamed.code()).isEqualTo(newCode);
    assertThat(renamed.name()).isEqualTo("Rename Me");
    assertThat(repository.findByCode(oldCode)).isEmpty();
    assertThat(repository.findByCode(newCode)).isPresent();
  }
}
