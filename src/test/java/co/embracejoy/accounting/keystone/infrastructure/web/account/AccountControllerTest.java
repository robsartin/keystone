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
import co.embracejoy.accounting.keystone.domain.security.PlatformAdminRepository;
import co.embracejoy.accounting.keystone.domain.security.Role;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRole;
import co.embracejoy.accounting.keystone.domain.security.TenantUserRoleRepository;
import co.embracejoy.accounting.keystone.domain.shared.Result;
import co.embracejoy.accounting.keystone.domain.tenancy.Tenant;
import co.embracejoy.accounting.keystone.domain.tenancy.TenantRepository;
import co.embracejoy.accounting.keystone.infrastructure.security.SecurityConfig;
import co.embracejoy.accounting.keystone.infrastructure.security.Tenants;
import co.embracejoy.accounting.keystone.testsupport.JwtTestSupport;
import co.embracejoy.accounting.keystone.testsupport.TestSecurityConfig;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(AccountController.class)
@Import({TestSecurityConfig.class, SecurityConfig.class})
@TestPropertySource(
    properties = {
      "keystone.security.issuer-uri=https://test.keystone.local/issuer",
      "keystone.security.audience=keystone-test-api"
    })
@DisplayName("AccountController")
class AccountControllerTest {

  @Autowired MockMvc mvc;
  @Autowired JwtTestSupport jwt;
  @MockitoBean AccountService service;
  @MockitoBean TenantRepository tenants;
  @MockitoBean TenantUserRoleRepository roles;
  @MockitoBean PlatformAdminRepository platformAdmins;

  private static final Currency USD = Currency.getInstance("USD");
  private static final AccountCode CODE_1000 = new AccountCode("1000");

  @BeforeEach
  void setupAuth() {
    Mockito.when(tenants.findById(Tenants.DEFAULT_TENANT_ID))
        .thenReturn(
            Optional.of(
                new Tenant(
                    Tenants.DEFAULT_TENANT_ID, "Test Tenant", Instant.now(), Optional.empty())));
    Mockito.when(platformAdmins.exists(Mockito.anyString())).thenReturn(false);
    Mockito.when(roles.findRole(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
  }

  private RequestPostProcessor withTestAuth(Role role) {
    Mockito.when(roles.findRole(Tenants.DEFAULT_TENANT_ID, "auth0|test-user"))
        .thenReturn(
            Optional.of(
                new TenantUserRole(
                    Tenants.DEFAULT_TENANT_ID, "auth0|test-user", role, Instant.EPOCH, "system")));
    return req -> {
      req.addHeader(
          "Authorization", "Bearer " + jwt.mint("auth0|test-user", Tenants.DEFAULT_TENANT_ID));
      return req;
    };
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
                    """)
                .with(withTestAuth(Role.ADMIN)))
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
                    """)
                .with(withTestAuth(Role.ADMIN)))
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
                    """)
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/account/parent-not-found")))
        .andExpect(jsonPath("$.detail", containsString("9000")));
  }

  @Test
  @DisplayName("GET /accounts returns 200 with list of accounts")
  void shouldReturn200WithAccountList() throws Exception {
    Mockito.when(service.findAll()).thenReturn(List.of(anAccount()));

    mvc.perform(get("/accounts").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].code").value("1000"));
  }

  @Test
  @DisplayName("GET /accounts/{code} returns 200 when found")
  void shouldReturn200WhenAccountFound() throws Exception {
    Mockito.when(service.findByCode(CODE_1000)).thenReturn(Optional.of(anAccount()));

    mvc.perform(get("/accounts/1000").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("1000"))
        .andExpect(jsonPath("$.name").value("Cash"));
  }

  @Test
  @DisplayName("GET /accounts/{code} returns 404 + not-found when missing")
  void shouldReturn404WhenAccountNotFound() throws Exception {
    Mockito.when(service.findByCode(Mockito.any())).thenReturn(Optional.empty());

    mvc.perform(get("/accounts/9999").with(withTestAuth(Role.ADMIN)))
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
                    """)
                .with(withTestAuth(Role.ADMIN)))
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
                    """)
                .with(withTestAuth(Role.ADMIN)))
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

    mvc.perform(post("/accounts/1000/deactivate").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(is(false)));
  }

  @Test
  @DisplayName("POST /accounts/{code}/reactivate returns 200")
  void shouldReturn200WhenReactivateSucceeds() throws Exception {
    Mockito.when(service.reactivate(CODE_1000)).thenReturn(Result.success(anAccount()));

    mvc.perform(post("/accounts/1000/reactivate").with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(is(true)));
  }

  @Test
  @DisplayName("POST /accounts/{code}/deactivate returns 404 when account not found")
  void shouldReturn404WhenDeactivatingMissingAccount() throws Exception {
    Mockito.when(service.deactivate(Mockito.any()))
        .thenReturn(Result.failure(new AccountError.NotFound(new AccountCode("9999"))));

    mvc.perform(post("/accounts/9999/deactivate").with(withTestAuth(Role.ADMIN)))
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
                    """)
                .with(withTestAuth(Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"));
  }

  @Test
  @DisplayName("POST /accounts returns 403 when caller has only the READ_ONLY role")
  void shouldReturn403WhenReadOnlyTriesCreate() throws Exception {
    mvc.perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code":"1000","name":"Cash","type":"ASSET","currency":"USD"}
                    """)
                .with(withTestAuth(Role.READ_ONLY)))
        .andExpect(status().isForbidden())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/problems/auth/insufficient-role")));
  }

  @Test
  @DisplayName("POST /accounts/{code}/deactivate returns 403 when caller has only BOOKKEEPER")
  void shouldReturn403WhenBookkeeperTriesDeactivate() throws Exception {
    mvc.perform(post("/accounts/1000/deactivate").with(withTestAuth(Role.BOOKKEEPER)))
        .andExpect(status().isForbidden())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value(endsWith("/problems/auth/insufficient-role")));
  }

  @Test
  @DisplayName("POST /accounts returns 201 when caller has the BOOKKEEPER role")
  void shouldAllowBookkeeperCreate() throws Exception {
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
                    """)
                .with(withTestAuth(Role.BOOKKEEPER)))
        .andExpect(status().isCreated());
  }
}
