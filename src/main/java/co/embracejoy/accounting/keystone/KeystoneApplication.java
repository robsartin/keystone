package co.embracejoy.accounting.keystone;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Spring Boot entry point for the keystone general ledger. */
@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(
    info =
        @Info(
            title = "Keystone",
            version = "0.1.0",
            description =
                "Keystone is a double-entry general-ledger HTTP API. Money is integer minor units"
                    + " (ISO 4217); journal entries are balanced postings in a configured base"
                    + " currency; periods are closed sequentially. See the project README and ADRs"
                    + " for design rationale.",
            license =
                @License(name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")))
public class KeystoneApplication {

  public static void main(String[] args) {
    SpringApplication.run(KeystoneApplication.class, args);
  }
}
