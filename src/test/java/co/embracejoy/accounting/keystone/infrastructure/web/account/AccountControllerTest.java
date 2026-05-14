package co.embracejoy.accounting.keystone.infrastructure.web.account;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.embracejoy.accounting.keystone.application.account.AccountService;
import co.embracejoy.accounting.keystone.domain.account.Account;
import co.embracejoy.accounting.keystone.domain.account.AccountCode;
import co.embracejoy.accounting.keystone.domain.account.AccountError;
import co.embracejoy.accounting.keystone.domain.account.AccountStatus;
import co.embracejoy.accounting.keystone.domain.account.AccountType;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.infrastructure.security.TenantContext;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
@DisplayName("AccountController")
class AccountControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean AccountService service;
  @MockitoBean TenantContext tenantContext;

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CODE_1000 = new AccountCode("1000");

  @BeforeEach
  void setupTenant() {
    Mockito.when(tenantContext.require()).thenReturn(Tenants.DEFAULT_TENANT_ID);
  }

  private static Account anAccount() {
    return new Account(
        Tenants.DEFAULT_TENANT_ID,
        CODE_1000,
        "Cash",
        AccountType.ASSET,
        USD,
        Optional.empty(),
        AccountStatus.ACTIVE);
  }

  @Test
  @DisplayName("POST /accounts returns 201 + Location when service succeeds")
  void shouldReturn201WhenCreateSucceeds() throws Exception {
    Mockito.when(
            service.create(
                Mockito.any(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
        .thenReturn(Result.success(anAccount()));

    mvc.perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code":"1000","name":"Cash","type":"ASSET","currency":"USD"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", endsWith("/accounts/1000")))
        .andExpect(jsonPath("$.code").value("1000"))
        .andExpect(jsonPath("$.type").value("ASSET"))
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  @DisplayName("POST /accounts returns 400 + code-already-exists when code is taken")
  void shouldReturn400WhenCodeAlreadyExists() throws Exception {
    Mockito.when(
            service.create(
                Mockito.any(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
        .thenReturn(Result.failure(new AccountError.CodeAlreadyExists(CODE_1000)));

    mvc.perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code":"1000","name":"Cash","type":"ASSET","currency":"USD"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/account/code-already-exists")))
        .andExpect(jsonPath("$.detail", containsString("1000")));
  }

  @Test
  @DisplayName("POST /accounts returns 400 + parent-not-found when parent is missing")
  void shouldReturn400WhenParentNotFound() throws Exception {
    Mockito.when(
            service.create(
                Mockito.any(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
        .thenReturn(Result.failure(new AccountError.ParentNotFound(new AccountCode("9000"))));

    mvc.perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code":"1001","name":"Cash Sub","type":"ASSET","currency":"USD","parentCode":"9000"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/account/parent-not-found")))
        .andExpect(jsonPath("$.detail", containsString("9000")));
  }

  @Test
  @DisplayName("GET /accounts returns 200 with list of accounts")
  void shouldReturn200WithAccountList() throws Exception {
    Mockito.when(service.findAll()).thenReturn(List.of(anAccount()));

    mvc.perform(get("/accounts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].code").value("1000"));
  }

  @Test
  @DisplayName("GET /accounts/{code} returns 200 when found")
  void shouldReturn200WhenAccountFound() throws Exception {
    Mockito.when(service.findByCode(CODE_1000)).thenReturn(Optional.of(anAccount()));

    mvc.perform(get("/accounts/1000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("1000"))
        .andExpect(jsonPath("$.name").value("Cash"));
  }

  @Test
  @DisplayName("GET /accounts/{code} returns 404 + not-found when missing")
  void shouldReturn404WhenAccountNotFound() throws Exception {
    Mockito.when(service.findByCode(Mockito.any())).thenReturn(Optional.empty());

    mvc.perform(get("/accounts/9999"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/account/not-found")))
        .andExpect(jsonPath("$.detail", containsString("9999")));
  }

  @Test
  @DisplayName("PATCH /accounts/{code} returns 200 when rename succeeds")
  void shouldReturn200WhenRenameSucceeds() throws Exception {
    Account renamed =
        new Account(
            Tenants.DEFAULT_TENANT_ID,
            new AccountCode("1001"),
            "Cash",
            AccountType.ASSET,
            USD,
            Optional.empty(),
            AccountStatus.ACTIVE);
    Mockito.when(service.rename(Mockito.any(), Mockito.any())).thenReturn(Result.success(renamed));
    Mockito.when(service.findByCode(new AccountCode("1001"))).thenReturn(Optional.of(renamed));

    mvc.perform(
            patch("/accounts/1000")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"newCode":"1001"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("1001"));
  }

  @Test
  @DisplayName("PATCH /accounts/{code} returns 400 + cycle-would-be-created")
  void shouldReturn400WhenCycleDetected() throws Exception {
    Mockito.when(service.setParent(Mockito.any(), Mockito.any()))
        .thenReturn(
            Result.failure(
                new AccountError.CycleWouldBeCreated(CODE_1000, new AccountCode("1001"))));

    mvc.perform(
            patch("/accounts/1000")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"newParentCode":"1001"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/account/cycle-would-be-created")));
  }

  @Test
  @DisplayName("POST /accounts/{code}/deactivate returns 200")
  void shouldReturn200WhenDeactivateSucceeds() throws Exception {
    Account inactive =
        new Account(
            Tenants.DEFAULT_TENANT_ID,
            CODE_1000,
            "Cash",
            AccountType.ASSET,
            USD,
            Optional.empty(),
            AccountStatus.INACTIVE);
    Mockito.when(service.deactivate(CODE_1000)).thenReturn(Result.success(inactive));

    mvc.perform(post("/accounts/1000/deactivate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(is(false)));
  }

  @Test
  @DisplayName("POST /accounts/{code}/reactivate returns 200")
  void shouldReturn200WhenReactivateSucceeds() throws Exception {
    Mockito.when(service.reactivate(CODE_1000)).thenReturn(Result.success(anAccount()));

    mvc.perform(post("/accounts/1000/reactivate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(is(true)));
  }

  @Test
  @DisplayName("POST /accounts/{code}/deactivate returns 404 when account not found")
  void shouldReturn404WhenDeactivatingMissingAccount() throws Exception {
    Mockito.when(service.deactivate(Mockito.any()))
        .thenReturn(Result.failure(new AccountError.NotFound(new AccountCode("9999"))));

    mvc.perform(post("/accounts/9999/deactivate"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/account/not-found")));
  }

  @Test
  @DisplayName("POST /accounts returns 400 when Bean Validation rejects the request")
  void shouldReturn400WhenRequestBodyInvalid() throws Exception {
    mvc.perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code":"","name":"","type":"INVALID","currency":"us"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"));
  }
}
