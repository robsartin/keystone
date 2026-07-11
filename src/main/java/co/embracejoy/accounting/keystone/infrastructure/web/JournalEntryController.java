package co.embracejoy.accounting.keystone.infrastructure.web;

import co.embracejoy.accounting.keystone.application.journal.JournalEntryQueryService;
import co.embracejoy.accounting.keystone.application.journal.PostJournalEntryService;
import co.embracejoy.accounting.keystone.application.journal.ReverseJournalEntryService;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryId;
import co.embracejoy.accounting.keystone.domain.journal.JournalEntryQuery;
import co.embracejoy.accounting.keystone.domain.journal.JournalError;
import co.embracejoy.accounting.keystone.domain.journal.PersistedJournalEntry;
import co.embracejoy.accounting.keystone.domain.journal.Posting;
import co.embracejoy.accounting.keystone.domain.journal.Side;
import co.embracejoy.accounting.keystone.domain.money.Money;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantId;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.JournalEntryResponse;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.ListJournalEntriesResponse;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.PostJournalEntryRequest;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.PostingRequest;
import co.embracejoy.accounting.keystone.infrastructure.web.dto.ReverseJournalEntryRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/journal-entries")
public class JournalEntryController {

  private final PostJournalEntryService service;
  private final JournalEntryQueryService queryService;
  private final ReverseJournalEntryService reverseService;
  private final Currency baseCurrency;
  private final TenantContext tenantContext;
  private final Counter postedOk;
  private final Counter postedInvalid;
  private final Timer postDuration;

  public JournalEntryController(
      PostJournalEntryService service,
      JournalEntryQueryService queryService,
      ReverseJournalEntryService reverseService,
      @Qualifier("keystoneBaseCurrency") Currency keystoneBaseCurrency,
      TenantContext tenantContext,
      @Qualifier("journalEntriesPostedOk") Counter journalEntriesPostedOk,
      @Qualifier("journalEntriesPostedInvalid") Counter journalEntriesPostedInvalid,
      @Qualifier("journalEntriesPostDuration") Timer journalEntriesPostDuration) {
    this.service = service;
    this.queryService = queryService;
    this.reverseService = reverseService;
    this.baseCurrency = keystoneBaseCurrency;
    this.tenantContext = tenantContext;
    this.postedOk = journalEntriesPostedOk;
    this.postedInvalid = journalEntriesPostedInvalid;
    this.postDuration = journalEntriesPostDuration;
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')")
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
    String actor = SecurityContextHolder.getContext().getAuthentication().getName();

    Result<PersistedJournalEntry, JournalError> result =
        service.post(tid, request.occurredOn(), request.description(), postings, actor);

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

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")
  @Operation(
      summary = "Fetch one journal entry",
      description =
          "Returns the entry with the given UUID, including reversal metadata (if this entry"
              + " reverses another OR has been reversed by another). 404 if not found.")
  public ResponseEntity<?> get(@PathVariable String id) {
    JournalEntryId jid;
    try {
      jid = new JournalEntryId(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return notFoundByRawId(id);
    }
    return queryService
        .findById(tenantContext.require(), jid)
        .<ResponseEntity<?>>map(p -> ResponseEntity.ok(JournalEntryResponse.of(p)))
        .orElseGet(() -> error(new JournalError.NotFound(jid)));
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER','READ_ONLY')")
  @Operation(
      summary = "List journal entries",
      description =
          "Cursor-paginated list of entries in the current tenant. Supports filtering by date"
              + " range (from/to), account (any posting touches this account code), description"
              + " substring (q, ILIKE), and total-debit amount range (amountMin/amountMax).")
  public ResponseEntity<?> list(
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String account,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Long amountMin,
      @RequestParam(required = false) Long amountMax,
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "50") int limit) {
    JournalEntryQuery query;
    try {
      query =
          new JournalEntryQuery(
              Optional.ofNullable(from),
              Optional.ofNullable(to),
              Optional.ofNullable(account).map(AccountCode::new),
              Optional.ofNullable(q),
              Optional.ofNullable(amountMin),
              Optional.ofNullable(amountMax),
              Optional.ofNullable(after).map(s -> new JournalEntryId(UUID.fromString(s))),
              limit);
    } catch (IllegalArgumentException e) {
      return invalidQuery(e.getMessage());
    }
    return ResponseEntity.ok(
        ListJournalEntriesResponse.of(queryService.findMany(tenantContext.require(), query)));
  }

  @PostMapping("/{id}/reverse")
  @PreAuthorize("hasAnyRole('ADMIN','BOOKKEEPER')")
  @Operation(
      operationId = "reverseJournalEntry",
      summary = "Reverse a journal entry",
      description =
          "Posts a mirror entry (debits/credits swapped) referencing the original via reverses_id."
              + " Reversal date is today; the original is never modified. Blocks reversing an"
              + " already-reversed entry (400 /journal/already-reversed) and unknown ids"
              + " (404 /journal/not-found).")
  public ResponseEntity<?> reverse(
      @PathVariable String id, @Valid @RequestBody ReverseJournalEntryRequest req) {
    JournalEntryId originalId;
    try {
      originalId = new JournalEntryId(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return notFoundByRawId(id);
    }
    String actor = SecurityContextHolder.getContext().getAuthentication().getName();
    return reverseService
        .reverse(tenantContext.require(), originalId, req.reason(), actor)
        .fold(
            persisted ->
                ResponseEntity.created(URI.create("/journal-entries/" + persisted.id().value()))
                    .body(JournalEntryResponse.of(persisted)),
            this::error);
  }

  private ResponseEntity<ProblemDetail> error(JournalError err) {
    ProblemDetail pd = ResultMapper.toProblemDetail(err);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }

  private ResponseEntity<ProblemDetail> notFoundByRawId(String rawId) {
    ProblemDetail pd = ResultMapper.journalNotFoundByRawId(rawId);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }

  private ResponseEntity<ProblemDetail> invalidQuery(String message) {
    ProblemDetail pd = ResultMapper.invalidJournalQuery(message);
    return ResponseEntity.status(pd.getStatus())
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .body(pd);
  }
}
