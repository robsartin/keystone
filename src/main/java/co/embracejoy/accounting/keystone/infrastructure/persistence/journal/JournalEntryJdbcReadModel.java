package co.embracejoy.accounting.keystone.infrastructure.persistence.journal;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryPage;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryQuery;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryReadModel;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.ReversalMetadata;
import co.embracejoy.accounting.keystone.domain.journal.ReversedByMetadata;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.RlsTransactionInterceptor;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JdbcClient-backed read model for journal-entry browsing.
 *
 * <p>{@code findMany}'s filters AND together against the raw {@code journal_entries} columns; the
 * account filter uses a correlated {@code EXISTS} against {@code postings}. Amount filters ({@code
 * amountMin}/{@code amountMax}) apply to the entry's aggregated total debit in the base currency
 * ({@code SUM(base_minor_units)} where {@code side = 'DEBIT'}) and MUST live in the {@code HAVING}
 * clause — filtering per-posting in {@code WHERE} would silently miss an entry whose individual
 * postings are each below the threshold but whose total is not.
 *
 * <p>Cursor pagination requests {@code limit + 1} rows ordered by {@code id ASC} (UUID v7 sorts by
 * time, so ascending id order doubles as chronological order); if the extra row came back, it is
 * dropped and the next cursor is the id of the last kept row.
 *
 * <p>Postings are hydrated in a second, bulk query keyed by the ids the first query returned —
 * avoids N+1 while keeping the primary query's {@code GROUP BY} limited to entry-level aggregation.
 *
 * <p>{@code findById} self-joins {@code journal_entries} to surface {@code reversedBy}: {@code r.id
 * AS reversed_by_id} etc. via {@code LEFT JOIN journal_entries r ON r.tenant_id = e.tenant_id AND
 * r.reverses_id = e.id}, so the same-tenant scope survives the join. {@code reverses} comes from
 * the row's own {@code reverses_id}/{@code reversal_reason} columns, same as {@code findMany}.
 */
@Repository
@Transactional(readOnly = true)
public class JournalEntryJdbcReadModel implements JournalEntryReadModel {

  private static final String FIND_ENTRIES_SQL =
      """
      SELECT e.id,
             e.occurred_on,
             e.description,
             e.reverses_id,
             e.reversal_reason
      FROM journal_entries e
      JOIN postings p ON p.journal_entry_id = e.id
      WHERE e.tenant_id = :tenant
        AND (CAST(:fromDate AS date) IS NULL OR e.occurred_on >= CAST(:fromDate AS date))
        AND (CAST(:toDate AS date) IS NULL OR e.occurred_on <= CAST(:toDate AS date))
        AND (CAST(:account AS varchar) IS NULL OR EXISTS (
              SELECT 1 FROM postings p2
              WHERE p2.journal_entry_id = e.id
                AND p2.account_code = CAST(:account AS varchar)))
        AND (CAST(:q AS varchar) IS NULL OR e.description ILIKE '%' || CAST(:q AS varchar) || '%')
        AND (CAST(:after AS uuid) IS NULL OR e.id > CAST(:after AS uuid))
      GROUP BY e.id
      HAVING (CAST(:amountMin AS bigint) IS NULL
              OR SUM(CASE WHEN p.side = 'DEBIT' THEN p.base_minor_units ELSE 0 END)
                   >= CAST(:amountMin AS bigint))
         AND (CAST(:amountMax AS bigint) IS NULL
              OR SUM(CASE WHEN p.side = 'DEBIT' THEN p.base_minor_units ELSE 0 END)
                   <= CAST(:amountMax AS bigint))
      ORDER BY e.id ASC
      LIMIT :limit
      """;

  private static final String FIND_BY_ID_SQL =
      """
      SELECT
        e.id, e.occurred_on, e.description,
        e.reverses_id, e.reversal_reason,
        r.id              AS reversed_by_id,
        r.posted_at       AS reversed_at,
        r.posted_by       AS reversed_by,
        r.reversal_reason AS reversed_reason
      FROM journal_entries e
      LEFT JOIN journal_entries r
        ON r.tenant_id = e.tenant_id
       AND r.reverses_id = e.id
      WHERE e.tenant_id = :tenant AND e.id = :id
      """;

  private static final String FIND_POSTINGS_SQL =
      """
      SELECT journal_entry_id, account_code, side, amount_minor_units, currency, base_minor_units
      FROM postings
      WHERE tenant_id = :tenant AND journal_entry_id IN (:ids)
      ORDER BY journal_entry_id, sequence_in_entry
      """;

  private final JdbcClient jdbc;
  private final Currency baseCurrency;
  private final TenantContext tenantContext;
  private final RlsTransactionInterceptor rlsInterceptor;

  public JournalEntryJdbcReadModel(
      JdbcClient jdbc,
      Currency baseCurrency,
      TenantContext tenantContext,
      RlsTransactionInterceptor rlsInterceptor) {
    this.jdbc = jdbc;
    this.baseCurrency = baseCurrency;
    this.tenantContext = tenantContext;
    this.rlsInterceptor = rlsInterceptor;
  }

  @Override
  public JournalEntryPage findMany(TenantId tenantId, JournalEntryQuery query) {
    requireTenantMatch(tenantId);
    rlsInterceptor.applyToCurrentTransaction();

    List<EntryRow> rows = fetchEntryRows(tenantId, query);
    boolean hasMore = rows.size() > query.limit();
    List<EntryRow> page = hasMore ? rows.subList(0, query.limit()) : rows;
    List<PersistedJournalEntry> items = hydrate(tenantId, page);
    Optional<JournalEntryId> nextCursor =
        hasMore ? Optional.of(items.get(items.size() - 1).id()) : Optional.empty();
    return new JournalEntryPage(items, nextCursor);
  }

  @Override
  public Optional<PersistedJournalEntry> findById(TenantId tenantId, JournalEntryId id) {
    requireTenantMatch(tenantId);
    rlsInterceptor.applyToCurrentTransaction();

    List<ReversedJoinRow> rows =
        jdbc.sql(FIND_BY_ID_SQL)
            .param("tenant", tenantId.value(), Types.OTHER)
            .param("id", id.value(), Types.OTHER)
            .query(JournalEntryJdbcReadModel::mapReversedJoinRow)
            .list();

    if (rows.isEmpty()) {
      return Optional.empty();
    }

    ReversedJoinRow row = rows.get(0);
    List<Posting> postings =
        fetchPostings(tenantId, List.of(row.id())).getOrDefault(row.id(), List.of());
    return Optional.of(toDomainWithReversedBy(tenantId, row, postings));
  }

  private List<EntryRow> fetchEntryRows(TenantId tenantId, JournalEntryQuery query) {
    return jdbc.sql(FIND_ENTRIES_SQL)
        .param("tenant", tenantId.value())
        .param("fromDate", query.from().orElse(null))
        .param("toDate", query.to().orElse(null))
        .param("account", query.account().map(AccountCode::value).orElse(null))
        .param("q", query.q().orElse(null))
        .param("after", query.after().map(JournalEntryId::value).orElse(null))
        .param("amountMin", query.amountMin().orElse(null))
        .param("amountMax", query.amountMax().orElse(null))
        .param("limit", query.limit() + 1)
        .query(JournalEntryJdbcReadModel::mapEntryRow)
        .list();
  }

  private List<PersistedJournalEntry> hydrate(TenantId tenantId, List<EntryRow> entryRows) {
    if (entryRows.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = entryRows.stream().map(EntryRow::id).toList();
    Map<UUID, List<Posting>> postingsByEntryId = fetchPostings(tenantId, ids);

    List<PersistedJournalEntry> result = new ArrayList<>(entryRows.size());
    for (EntryRow row : entryRows) {
      result.add(toDomain(tenantId, row, postingsByEntryId.getOrDefault(row.id(), List.of())));
    }
    return result;
  }

  private Map<UUID, List<Posting>> fetchPostings(TenantId tenantId, List<UUID> ids) {
    return jdbc
        .sql(FIND_POSTINGS_SQL)
        .param("tenant", tenantId.value())
        .param("ids", ids)
        .query(
            (rs, rowNum) ->
                Map.entry(rs.getObject("journal_entry_id", UUID.class), buildPosting(rs)))
        .list()
        .stream()
        .collect(
            Collectors.groupingBy(
                Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
  }

  private Posting buildPosting(ResultSet rs) throws SQLException {
    Currency txCurrency = Currency.getInstance(rs.getString("currency"));
    Money amount = new Money(rs.getLong("amount_minor_units"), txCurrency);
    Money baseAmount = new Money(rs.getLong("base_minor_units"), baseCurrency);
    return new Posting(
        new AccountCode(rs.getString("account_code")),
        Side.valueOf(rs.getString("side")),
        amount,
        baseAmount);
  }

  private PersistedJournalEntry toDomain(TenantId tenantId, EntryRow row, List<Posting> postings) {
    JournalEntry entry = new JournalEntry(tenantId, row.occurredOn(), row.description(), postings);
    Optional<ReversalMetadata> reverses =
        row.reversesId() == null
            ? Optional.empty()
            : Optional.of(
                new ReversalMetadata(new JournalEntryId(row.reversesId()), row.reversalReason()));
    return new PersistedJournalEntry(
        new JournalEntryId(row.id()), entry, reverses, Optional.empty());
  }

  private PersistedJournalEntry toDomainWithReversedBy(
      TenantId tenantId, ReversedJoinRow row, List<Posting> postings) {
    JournalEntry entry = new JournalEntry(tenantId, row.occurredOn(), row.description(), postings);
    Optional<ReversalMetadata> reverses =
        row.reversesId() == null
            ? Optional.empty()
            : Optional.of(
                new ReversalMetadata(new JournalEntryId(row.reversesId()), row.reversalReason()));
    Optional<ReversedByMetadata> reversedBy =
        row.reversedById() == null
            ? Optional.empty()
            : Optional.of(
                new ReversedByMetadata(
                    new JournalEntryId(row.reversedById()),
                    row.reversedAt(),
                    row.reversedByActor(),
                    row.reversedReason()));
    return new PersistedJournalEntry(new JournalEntryId(row.id()), entry, reverses, reversedBy);
  }

  private void requireTenantMatch(TenantId tenantId) {
    TenantId contextTid = tenantContext.require();
    if (!contextTid.equals(tenantId)) {
      throw new IllegalStateException(
          "tenant mismatch — requested " + tenantId + ", context is " + contextTid);
    }
  }

  private static EntryRow mapEntryRow(ResultSet rs, int rowNum) throws SQLException {
    return new EntryRow(
        rs.getObject("id", UUID.class),
        rs.getObject("occurred_on", LocalDate.class),
        rs.getString("description"),
        rs.getObject("reverses_id", UUID.class),
        rs.getString("reversal_reason"));
  }

  private record EntryRow(
      UUID id, LocalDate occurredOn, String description, UUID reversesId, String reversalReason) {}

  private static ReversedJoinRow mapReversedJoinRow(ResultSet rs, int rowNum) throws SQLException {
    Timestamp reversedAtTs = rs.getTimestamp("reversed_at");
    return new ReversedJoinRow(
        rs.getObject("id", UUID.class),
        rs.getObject("occurred_on", LocalDate.class),
        rs.getString("description"),
        rs.getObject("reverses_id", UUID.class),
        rs.getString("reversal_reason"),
        rs.getObject("reversed_by_id", UUID.class),
        reversedAtTs == null ? null : reversedAtTs.toInstant(),
        rs.getString("reversed_by"),
        rs.getString("reversed_reason"));
  }

  private record ReversedJoinRow(
      UUID id,
      LocalDate occurredOn,
      String description,
      UUID reversesId,
      String reversalReason,
      UUID reversedById,
      Instant reversedAt,
      String reversedByActor,
      String reversedReason) {}
}
