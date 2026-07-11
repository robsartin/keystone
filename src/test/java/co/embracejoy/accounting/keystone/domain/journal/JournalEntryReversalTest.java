package co.embracejoy.accounting.keystone.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JournalEntry.reverse")
class JournalEntryReversalTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("01902f9f-0000-7000-8000-00000000d1f1"));
  private static final JournalEntryId ORIGINAL =
      new JournalEntryId(UUID.fromString("01902f9f-0000-7000-8000-000000000001"));
  private static final LocalDate TODAY = LocalDate.of(2026, 7, 9);

  @Test
  @DisplayName("swaps debit and credit sides on every posting")
  void shouldSwapSidesWhenReversing() {
    JournalEntry original =
        new JournalEntry(
            TENANT,
            LocalDate.of(2026, 6, 15),
            "original",
            List.of(
                new Posting(new AccountCode("1000"), Side.DEBIT, money(1000), money(1000)),
                new Posting(new AccountCode("3000"), Side.CREDIT, money(1000), money(1000))));

    JournalEntry reversed = JournalEntry.reverse(ORIGINAL, "wrong account", TODAY, original);

    assertThat(reversed.postings())
        .extracting(Posting::side)
        .containsExactly(Side.CREDIT, Side.DEBIT);
  }

  @Test
  @DisplayName("uses today's date, not the original's occurred date")
  void shouldUseTodaysDateWhenReversing() {
    JournalEntry original = anEntry();
    JournalEntry reversed = JournalEntry.reverse(ORIGINAL, "typo", TODAY, original);
    assertThat(reversed.occurredOn()).isEqualTo(TODAY);
  }

  @Test
  @DisplayName("description is prefixed with 'Reversal of #<id>: <reason>'")
  void shouldPrefixDescriptionWithOriginalIdAndReason() {
    JournalEntry original = anEntry();
    JournalEntry reversed = JournalEntry.reverse(ORIGINAL, "typo", TODAY, original);
    assertThat(reversed.description()).isEqualTo("Reversal of #" + ORIGINAL.value() + ": typo");
  }

  @Test
  @DisplayName("preserves currency and amounts leg by leg")
  void shouldPreserveAmountsWhenReversing() {
    JournalEntry original = anEntry();
    JournalEntry reversed = JournalEntry.reverse(ORIGINAL, "typo", TODAY, original);
    assertThat(reversed.postings().get(0).amount()).isEqualTo(original.postings().get(0).amount());
    assertThat(reversed.postings().get(0).account())
        .isEqualTo(original.postings().get(0).account());
  }

  @Test
  @DisplayName("uses the original entry's tenant")
  void shouldUseOriginalTenantWhenReversing() {
    JournalEntry original = anEntry();
    JournalEntry reversed = JournalEntry.reverse(ORIGINAL, "typo", TODAY, original);
    assertThat(reversed.tenantId()).isEqualTo(original.tenantId());
  }

  @Test
  @DisplayName("rejects blank reason")
  void shouldRejectBlankReason() {
    JournalEntry original = anEntry();
    assertThatThrownBy(() -> JournalEntry.reverse(ORIGINAL, "  ", TODAY, original))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static JournalEntry anEntry() {
    return new JournalEntry(
        TENANT,
        LocalDate.of(2026, 6, 15),
        "original",
        List.of(
            new Posting(new AccountCode("1000"), Side.DEBIT, money(1000), money(1000)),
            new Posting(new AccountCode("3000"), Side.CREDIT, money(1000), money(1000))));
  }

  private static Money money(long minor) {
    return new Money(minor, USD);
  }
}
