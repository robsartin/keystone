package co.embracejoy.accounting.keystone.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AccountError")
class AccountErrorTest {

  private static final AccountCode CODE = new AccountCode("1000");
  private static final AccountCode PARENT = new AccountCode("1");

  @Test
  @DisplayName("CodeAlreadyExists carries the offending code")
  void codeAlreadyExistsCarriesCode() {
    AccountError e = new AccountError.CodeAlreadyExists(CODE);
    assertInstanceOf(AccountError.CodeAlreadyExists.class, e);
    assertEquals(CODE, ((AccountError.CodeAlreadyExists) e).code());
  }

  @Test
  @DisplayName("NotFound carries the queried code")
  void notFoundCarriesCode() {
    AccountError e = new AccountError.NotFound(CODE);
    assertEquals(CODE, ((AccountError.NotFound) e).code());
  }

  @Test
  @DisplayName("ParentNotFound carries the parent code")
  void parentNotFoundCarriesParent() {
    AccountError e = new AccountError.ParentNotFound(PARENT);
    assertEquals(PARENT, ((AccountError.ParentNotFound) e).parentCode());
  }

  @Test
  @DisplayName("CycleWouldBeCreated carries the offending child and target parent")
  void cycleCarriesBothCodes() {
    AccountError e = new AccountError.CycleWouldBeCreated(CODE, PARENT);
    AccountError.CycleWouldBeCreated c = (AccountError.CycleWouldBeCreated) e;
    assertEquals(CODE, c.child());
    assertEquals(PARENT, c.proposedParent());
  }

  @Test
  @DisplayName("CodeInUseByPosting carries the rename-target code")
  void codeInUseCarriesCode() {
    AccountError e = new AccountError.CodeInUseByPosting(CODE);
    assertEquals(CODE, ((AccountError.CodeInUseByPosting) e).code());
  }

  @Test
  @DisplayName("AccountError is sealed and lists every variant")
  void sealedListIsComplete() {
    // Five permitted subtypes — pin the count so any future variant trips a sealed-switch
    // somewhere or this test.
    assertEquals(5, AccountError.class.getPermittedSubclasses().length);
  }
}
