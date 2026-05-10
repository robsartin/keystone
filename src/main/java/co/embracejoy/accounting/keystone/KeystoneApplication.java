package co.embracejoy.accounting.keystone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for the keystone general ledger. */
@SpringBootApplication
public class KeystoneApplication {

  public static void main(String[] args) {
    SpringApplication.run(KeystoneApplication.class, args);
  }
}
