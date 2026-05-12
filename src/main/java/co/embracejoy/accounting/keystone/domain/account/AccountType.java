package co.embracejoy.accounting.keystone.domain.account;

/** The five standard account types. */
public enum AccountType {
  ASSET(NormalSide.DEBIT),
  LIABILITY(NormalSide.CREDIT),
  EQUITY(NormalSide.CREDIT),
  REVENUE(NormalSide.CREDIT),
  EXPENSE(NormalSide.DEBIT);

  private final NormalSide normalSide;

  AccountType(NormalSide normalSide) {
    this.normalSide = normalSide;
  }

  public NormalSide normalSide() {
    return normalSide;
  }
}
