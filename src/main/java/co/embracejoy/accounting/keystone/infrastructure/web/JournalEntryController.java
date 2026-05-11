package co.embracejoy.accounting.keystone.infrastructure.web;

import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.JournalEntryResponse;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.PostJournalEntryRequest;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.PostingRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Currency;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for {@code POST /journal-entries}. */
@RestController
@RequestMapping("/journal-entries")
public class JournalEntryController {

  private final PostJournalEntryService service;

  public JournalEntryController(PostJournalEntryService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<?> post(@Valid @RequestBody PostJournalEntryRequest request) {
    Currency currency = Currency.getInstance(request.currency());
    List<Posting> postings =
        request.postings().stream().map(p -> toDomainPosting(p, currency)).toList();

    Result<PersistedJournalEntry, JournalError> result =
        service.post(request.occurredOn(), request.description(), postings);

    return result.fold(
        persisted ->
            ResponseEntity.created(URI.create("/journal-entries/" + persisted.id().value()))
                .body(JournalEntryResponse.of(persisted)),
        error ->
            ResponseEntity.badRequest()
                .contentType(MediaType.parseMediaType("application/problem+json"))
                .body(ResultMapper.toProblemDetail(error)));
  }

  private static Posting toDomainPosting(PostingRequest p, Currency currency) {
    return new Posting(
        new AccountCode(p.account()), Side.valueOf(p.side()), new Money(p.minorUnits(), currency));
  }
}
