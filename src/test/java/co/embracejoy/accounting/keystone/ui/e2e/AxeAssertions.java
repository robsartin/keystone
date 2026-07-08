package co.embracejoy.accounting.keystone.ui.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Runs the vendored axe-core engine (4.10.2, {@code src/test/resources/axe.min.js}) against the
 * current page and fails the calling test if any WCAG 2.0/2.1 A or AA violation is reported.
 *
 * <p>axe-core is injected fresh via {@link Page#addScriptTag} on every call rather than once per
 * page load: each {@link com.microsoft.playwright.BrowserContext} in {@code AdminUiE2EIT} is
 * short-lived (one per test), and re-injecting is cheap relative to the browser round-trip {@code
 * axe.run} itself performs.
 *
 * <p>Parks the mouse at the origin and waits out Bootstrap's {@code .15s} button transition before
 * every run: an HTMX row removal (outerHTML swap) can shift a sibling row up underneath wherever
 * the cursor was last left by a click, genuinely re-triggering {@code :hover} on it via the reflow
 * alone (no mouse movement needed — {@code :hover} is recomputed against current layout). Moving
 * the mouse away then starts the reverse hover-exit transition; sampling immediately catches axe
 * mid-fade and reports a spurious {@code color-contrast} violation for a blended frame no user
 * would ever perceive. {@link #SETTLE_MILLIS} comfortably exceeds Bootstrap's transition duration.
 */
public final class AxeAssertions {

  private static final String AXE_SOURCE = readAxe();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final double SETTLE_MILLIS = 250;

  private AxeAssertions() {
    // static utility class; no instances
  }

  public static void assertNoViolations(Page page) throws Exception {
    page.mouse().move(0, 0);
    page.waitForTimeout(SETTLE_MILLIS);
    page.addScriptTag(new Page.AddScriptTagOptions().setContent(AXE_SOURCE));
    Object raw =
        page.evaluate(
            "async () => JSON.stringify(await axe.run(document, "
                + "{runOnly: {type: 'tag', values: ['wcag2a', 'wcag2aa']}}))");
    List<Map<String, Object>> violations = extractViolations((String) raw);
    assertThat(violations)
        .as("Expected no WCAG AA violations on %s, found: %s", page.url(), summarize(violations))
        .isEmpty();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> extractViolations(String reportJson) throws Exception {
    Map<String, Object> report = MAPPER.readValue(reportJson, Map.class);
    return (List<Map<String, Object>>) report.get("violations");
  }

  private static String summarize(List<Map<String, Object>> violations) {
    return violations.stream().map(v -> String.valueOf(v.get("id"))).toList().toString();
  }

  private static String readAxe() {
    try {
      return Files.readString(Paths.get("src/test/resources/axe.min.js"), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("cannot read axe.min.js", e);
    }
  }
}
