package co.embracejoy.accounting.keystone.infrastructure.web;

import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.JournalEntryResponse;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.PostJournalEntryRequest;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.PostingRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Currency;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/journal-entries")
public class JournalEntryController {

  private final PostJournalEntryService service;
  private final Currency baseCurrency;
  private final TenantContext tenantContext;
  private final Counter postedOk;
  private final Counter postedInvalid;
  private final Timer postDuration;

  public JournalEntryController(
      PostJournalEntryService service,
      @Qualifier("keystoneBaseCurrency") Currency keystoneBaseCurrency,
      TenantContext tenantContext,
      @Qualifier("journalEntriesPostedOk") Counter journalEntriesPostedOk,
      @Qualifier("journalEntriesPostedInvalid") Counter journalEntriesPostedInvalid,
      @Qualifier("journalEntriesPostDuration") Timer journalEntriesPostDuration) {
    this.service = service;
    this.baseCurrency = keystoneBaseCurrency;
    this.tenantContext = tenantContext;
    this.postedOk = journalEntriesPostedOk;
    this.postedInvalid = journalEntriesPostedInvalid;
    this.postDuration = journalEntriesPostDuration;
  }

  @PostMapping
  @Operation(
      summary = "Post a journal entry",
      description =
          "Persists a balanced double-entry journal entry. Each posting carries a transaction"
              + " amount and a base-currency equivalent (baseMinorUnits); the entry must balance"
              + " in the configured base currency. Postings against an unknown, inactive, or"
              + " non-leaf account are rejected; so are postings into a closed period.")
  public ResponseEntity<?> post(@Valid @RequestBody PostJournalEntryRequest request) {
    TenantId tid = tenantContext.require();
    return postDuration.record(() -> handle(tid, request));
  }

  private ResponseEntity<?> handle(TenantId tid, PostJournalEntryRequest request) {
    List<Posting> postings = request.postings().stream().map(this::toDomainPosting).toList();

    Result<PersistedJournalEntry, JournalError> result =
        service.post(tid, request.occurredOn(), request.description(), postings);

    return result.fold(
        persisted -> {
          postedOk.increment();
          return ResponseEntity.created(URI.create("/journal-entries/" + persisted.id().value()))
              .body(JournalEntryResponse.of(persisted));
        },
        error -> {
          postedInvalid.increment();
          return ResponseEntity.badRequest()
              .contentType(MediaType.parseMediaType("application/problem+json"))
              .body(ResultMapper.toProblemDetail(error));
        });
  }

  private Posting toDomainPosting(PostingRequest p) {
    Currency txCurrency = Currency.getInstance(p.currency());
    Money amount = new Money(p.minorUnits(), txCurrency);
    Money baseAmount = new Money(p.baseMinorUnits(), baseCurrency);
    return new Posting(new AccountCode(p.account()), Side.valueOf(p.side()), amount, baseAmount);
  }
}
