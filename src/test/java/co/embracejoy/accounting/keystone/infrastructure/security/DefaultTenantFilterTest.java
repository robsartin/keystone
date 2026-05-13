package co.embracejoy.accounting.keystone.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DisplayName("DefaultTenantFilter")
class DefaultTenantFilterTest {

  @Mock HttpServletRequest req;
  @Mock HttpServletResponse res;
  @Mock FilterChain chain;

  private TenantContext tenantContext;
  private DefaultTenantFilter filter;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    tenantContext = new TenantContext();
    filter = new DefaultTenantFilter(tenantContext);
  }

  @Test
  @DisplayName("populates TenantContext with the default tenant before delegating")
  void shouldSetDefaultTenantBeforeChain() throws Exception {
    filter.doFilter(req, res, chain);
    assertThat(tenantContext.require()).isEqualTo(Tenants.DEFAULT_TENANT_ID);
    verify(chain, times(1)).doFilter(req, res);
  }
}
