package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "journal_entries")
class JournalEntryEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "occurred_on", nullable = false)
  private LocalDate occurredOn;

  @Column(name = "description", nullable = false, length = 500)
  private String description;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
  private Instant createdAt;

  @OneToMany(
      mappedBy = "journalEntry",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.EAGER)
  @OrderColumn(name = "sequence_in_entry")
  private List<PostingEntity> postings = new ArrayList<>();

  protected JournalEntryEntity() {
    // JPA required no-arg constructor
  }

  JournalEntryEntity(UUID id, LocalDate occurredOn, String description, String currency) {
    this.id = id;
    this.occurredOn = occurredOn;
    this.description = description;
    this.currency = currency;
  }

  UUID getId() {
    return id;
  }

  LocalDate getOccurredOn() {
    return occurredOn;
  }

  String getDescription() {
    return description;
  }

  String getCurrency() {
    return currency;
  }

  List<PostingEntity> getPostings() {
    return postings;
  }

  void addPosting(PostingEntity posting) {
    posting.setJournalEntry(this);
    postings.add(posting);
  }
}
