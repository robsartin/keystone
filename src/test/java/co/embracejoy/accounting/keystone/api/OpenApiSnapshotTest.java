package co.embracejoy.accounting.keystone.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@DisplayName("OpenAPI snapshot")
@EnabledIfSystemProperty(named = "openapi.gate", matches = "true")
class OpenApiSnapshotTest {

  @Test
  @DisplayName("generated spec matches the committed snapshot")
  void shouldMatchCommittedSnapshot() throws Exception {
    Path generated = Path.of("target", "openapi.yaml");
    Path committed = Path.of("docs", "openapi", "openapi.yaml");

    assertThat(generated).exists();
    assertThat(committed).exists();

    String generatedContent = normalize(Files.readString(generated));
    String committedContent = normalize(Files.readString(committed));

    assertThat(generatedContent)
        .as(
            "If this fails, the API surface changed. Run "
                + "`./mvnw -Popenapi-update verify` and commit "
                + "docs/openapi/openapi.yaml together with your code change.")
        .isEqualTo(committedContent);
  }

  private static String normalize(String s) {
    return s.replace("\r\n", "\n").trim();
  }
}
