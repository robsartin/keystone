package co.embracejoy.accounting.keystone.infrastructure.persistence.reports;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceReadModel;
import co.embracejoy.accounting.keystone.domain.reports.TrialBalanceRow;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for {@link TrialBalanceReadModel}.
 *
 * <p>One GROUP BY query against {@code postings} joined to {@code journal_entries}; rows are
 * filtered by {@code je.occurred_on <= :asOf} and (optionally) {@code balance != 0}. Uses Spring
 * 6.1+ {@link JdbcClient} (the modern fluent client) with named parameters — the {@code
 * :includeZero} flag is referenced once in the {@code HAVING} clause and Spring binds it for us.
 */
@Repository
@Transactional(readOnly = true)
public class TrialBalanceJdbcReadModel implements TrialBalanceReadModel {

  private static final String SQL =
      """
      SELECT p.account_code,
             p.currency,
             SUM(CASE WHEN p.side = 'DEBIT'  THEN p.amount_minor_units  ELSE 0 END) AS debits,
             SUM(CASE WHEN p.side = 'CREDIT' THEN p.amount_minor_units  ELSE 0 END) AS credits,
             SUM(CASE WHEN p.side = 'DEBIT'  THEN p.base_minor_units    ELSE 0 END) AS base_debits,
             SUM(CASE WHEN p.side = 'CREDIT' THEN p.base_minor_units    ELSE 0 END) AS base_credits
      FROM   postings p
      JOIN   journal_entries je ON je.id = p.journal_entry_id
      WHERE  je.occurred_on <= :asOf
      GROUP  BY p.account_code, p.currency
      HAVING :includeZero OR (SUM(CASE WHEN p.side = 'DEBIT'  THEN p.amount_minor_units ELSE 0 END)
                            - SUM(CASE WHEN p.side = 'CREDIT' THEN p.amount_minor_units ELSE 0 END)) <> 0
      ORDER  BY p.account_code, p.currency
      """;

  private static final RowMapper<TrialBalanceRow> MAPPER =
      (rs, rowNum) ->
          new TrialBalanceRow(
              new AccountCode(rs.getString("account_code")),
              Currency.getInstance(rs.getString("currency")),
              rs.getLong("debits"),
              rs.getLong("credits"),
              rs.getLong("base_debits"),
              rs.getLong("base_credits"));

  private final JdbcClient jdbc;

  public TrialBalanceJdbcReadModel(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<TrialBalanceRow> fetch(LocalDate asOf, boolean includeZero) {
    return jdbc.sql(SQL).param("asOf", asOf).param("includeZero", includeZero).query(MAPPER).list();
  }
}
