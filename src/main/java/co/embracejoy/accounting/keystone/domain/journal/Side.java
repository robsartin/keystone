package co.embracejoy.accounting.keystone.domain.journal;

/** Which side of the ledger a posting affects. */
public enum Side {
  DEBIT,
  CREDIT;

  /** The opposite side: DEBIT ↔ CREDIT. Used when mirroring postings for a reversal entry. */
  public Side opposite() {
    return this == DEBIT ? CREDIT : DEBIT;
  }
}
