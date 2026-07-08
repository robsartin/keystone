package co.embracejoy.accounting.keystone.infrastructure.web.ui;

import static org.assertj.core.api.Assertions.assertThat;

import co.embracejoy.accounting.keystone.infrastructure.web.ui.dto.AlertView;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Exercises {@link UiExceptionHandler} directly rather than through {@code MockMvc}: both exception
 * types it handles are thrown by the framework before a controller method body runs, so there's no
 * domain behavior worth driving end-to-end here — only the handler's own mapping from exception to
 * {@link AlertView} + HTTP status.
 */
@DisplayName("UiExceptionHandler")
class UiExceptionHandlerTest {

  private final UiExceptionHandler handler = new UiExceptionHandler();

  @Test
  @DisplayName("MethodArgumentNotValidException renders the alert fragment with 400")
  void shouldRenderAlertOnValidationError() throws NoSuchMethodException {
    Method target = getClass().getDeclaredMethod("target", TestForm.class);
    BindingResult binding = new BeanPropertyBindingResult(new TestForm(), "form");
    binding.rejectValue("name", "NotBlank", "must not be blank");
    MethodArgumentNotValidException ex =
        new MethodArgumentNotValidException(new MethodParameter(target, 0), binding);

    Model model = new ExtendedModelMap();
    MockHttpServletResponse response = new MockHttpServletResponse();

    String view = handler.onValidationError(ex, model, response);

    assertThat(view).isEqualTo("fragments/alert :: alert");
    assertThat(response.getStatus()).isEqualTo(400);
    AlertView alert = (AlertView) model.getAttribute("alert");
    assertThat(alert.severity()).isEqualTo("warning");
    assertThat(alert.title()).isEqualTo("Invalid input");
    assertThat(alert.detail()).contains("must not be blank");
  }

  @Test
  @DisplayName("AccessDeniedException on a plain navigation renders the full error page with 403")
  void shouldRenderErrorPageOnAccessDeniedForPlainNavigation() {
    AccessDeniedException ex = new AccessDeniedException("denied");

    Model model = new ExtendedModelMap();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    String view = handler.onAccessDenied(ex, model, request, response);

    assertThat(view).isEqualTo("error");
    assertThat(response.getStatus()).isEqualTo(403);
    AlertView alert = (AlertView) model.getAttribute("alert");
    assertThat(alert.severity()).isEqualTo("danger");
    assertThat(alert.title()).isEqualTo("Not allowed");
  }

  @Test
  @DisplayName("AccessDeniedException on an HTMX request renders the bare alert fragment with 403")
  void shouldRenderBareFragmentOnAccessDeniedForHtmxRequest() {
    AccessDeniedException ex = new AccessDeniedException("denied");

    Model model = new ExtendedModelMap();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("HX-Request", "true");
    MockHttpServletResponse response = new MockHttpServletResponse();

    String view = handler.onAccessDenied(ex, model, request, response);

    assertThat(view).isEqualTo("fragments/alert :: alert");
    assertThat(response.getStatus()).isEqualTo(403);
  }

  /** Reflection target only — supplies a real {@link Method} for {@link MethodParameter}. */
  private void target(TestForm form) {
    // unused
  }

  private static final class TestForm {
    private String name;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }
}
