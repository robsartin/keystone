package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "postings")
class PostingEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "journal_entry_id", nullable = false, updatable = false)
  private JournalEntryEntity journalEntry;

  @Column(name = "account_code", nullable = false, length = 64)
  private String accountCode;

  @Column(name = "side", nullable = false, length = 6)
  private String side;

  @Column(name = "amount_minor_units", nullable = false)
  private long amountMinorUnits;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "base_minor_units", nullable = false)
  private long baseMinorUnits;

  @Column(name = "sequence_in_entry", nullable = false)
  private int sequenceInEntry;

  protected PostingEntity() {
    // JPA required no-arg constructor
  }

  PostingEntity(
      UUID id,
      String accountCode,
      String side,
      long amountMinorUnits,
      String currency,
      long baseMinorUnits,
      int sequenceInEntry) {
    this.id = id;
    this.accountCode = accountCode;
    this.side = side;
    this.amountMinorUnits = amountMinorUnits;
    this.currency = currency;
    this.baseMinorUnits = baseMinorUnits;
    this.sequenceInEntry = sequenceInEntry;
  }

  UUID getId() {
    return id;
  }

  String getAccountCode() {
    return accountCode;
  }

  String getSide() {
    return side;
  }

  long getAmountMinorUnits() {
    return amountMinorUnits;
  }

  String getCurrency() {
    return currency;
  }

  long getBaseMinorUnits() {
    return baseMinorUnits;
  }

  JournalEntryEntity getJournalEntry() {
    return journalEntry;
  }

  void setJournalEntry(JournalEntryEntity journalEntry) {
    this.journalEntry = journalEntry;
  }
}
