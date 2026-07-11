package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "occurred_on", nullable = false)
  private LocalDate occurredOn;

  @Column(name = "description", nullable = false, length = 500)
  private String description;

  @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
  private Instant createdAt;

  @Column(name = "reverses_id")
  private UUID reversesId;

  @Column(name = "reversal_reason")
  private String reversalReason;

  @Column(name = "posted_at", nullable = false, updatable = false, insertable = false)
  private Instant postedAt;

  @Column(name = "posted_by")
  private String postedBy;

  // Hibernate's HHH160246: a 'mappedBy' association may not also use @OrderColumn.
  // The application sets PostingEntity.sequenceInEntry explicitly via the mapper's loop
  // index; @OrderBy here just keeps reads deterministic.
  @OneToMany(
      mappedBy = "journalEntry",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.EAGER)
  @OrderBy("sequenceInEntry ASC")
  private List<PostingEntity> postings = new ArrayList<>();

  protected JournalEntryEntity() {
    // JPA required no-arg constructor
  }

  JournalEntryEntity(
      UUID id,
      UUID tenantId,
      LocalDate occurredOn,
      String description,
      UUID reversesId,
      String reversalReason,
      String postedBy) {
    this.id = id;
    this.tenantId = tenantId;
    this.occurredOn = occurredOn;
    this.description = description;
    this.reversesId = reversesId;
    this.reversalReason = reversalReason;
    this.postedBy = postedBy;
  }

  UUID getId() {
    return id;
  }

  UUID getTenantId() {
    return tenantId;
  }

  LocalDate getOccurredOn() {
    return occurredOn;
  }

  String getDescription() {
    return description;
  }

  UUID getReversesId() {
    return reversesId;
  }

  String getReversalReason() {
    return reversalReason;
  }

  Instant getPostedAt() {
    return postedAt;
  }

  String getPostedBy() {
    return postedBy;
  }

  List<PostingEntity> getPostings() {
    return postings;
  }

  void addPosting(PostingEntity posting) {
    posting.setJournalEntry(this);
    posting.setTenantId(this.tenantId);
    postings.add(posting);
  }
}
