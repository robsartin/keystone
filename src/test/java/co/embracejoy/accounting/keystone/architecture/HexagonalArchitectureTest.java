package co.embracejoy.accounting.keystone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit rules enforcing keystone's hexagonal layering.
 *
 * <p>{@link com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests} is load-bearing: the
 * {@code NO_PUBLIC_METHOD_RETURNS_THROWABLE} rule below would otherwise trip on JUnit's {@code
 * assertThrows} (which legitimately returns {@code Throwable}) and other test utilities. Do not
 * remove the import option without first re-scoping that rule.
 */
@AnalyzeClasses(
    packages = "co.embracejoy.accounting.keystone",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class HexagonalArchitectureTest {

  @ArchTest
  static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_APPLICATION =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..application..");

  @ArchTest
  static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_INFRASTRUCTURE =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure..");

  @ArchTest
  static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure..");

  @ArchTest
  static final ArchRule DOMAIN_DOES_NOT_IMPORT_SPRING =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..");

  @ArchTest
  static final ArchRule DOMAIN_DOES_NOT_IMPORT_JPA =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("jakarta.persistence..");

  @ArchTest
  static final ArchRule DOMAIN_DOES_NOT_IMPORT_JACKSON =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.fasterxml.jackson..");

  @ArchTest
  static final ArchRule DOMAIN_DOES_NOT_IMPORT_SLF4J =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.slf4j..");

  @ArchTest
  static final ArchRule APPLICATION_DOES_NOT_IMPORT_SPRING =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..");

  @ArchTest
  static final ArchRule NO_PUBLIC_METHOD_RETURNS_THROWABLE =
      methods().that().arePublic().should().notHaveRawReturnType(Throwable.class);

  @ArchTest
  static final ArchRule CLASSES_ARE_IN_EXPECTED_TOP_LEVEL_PACKAGES =
      classes()
          .that()
          .resideInAPackage("co.embracejoy.accounting.keystone..")
          .should()
          .resideInAnyPackage(
              "co.embracejoy.accounting.keystone",
              "co.embracejoy.accounting.keystone.domain..",
              "co.embracejoy.accounting.keystone.application..",
              "co.embracejoy.accounting.keystone.infrastructure..");

  @ArchTest
  static final ArchRule WEB_DOES_NOT_DEPEND_ON_PERSISTENCE_ENTITIES =
      noClasses()
          .that()
          .resideInAPackage("..infrastructure.web..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure.persistence..");

  @ArchTest
  static final ArchRule CONTROLLERS_LIVE_IN_WEB_PACKAGE =
      classes()
          .that()
          .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
          .should()
          .resideInAPackage("..infrastructure.web..");

  @ArchTest
  static final ArchRule ACCOUNT_DOES_NOT_DEPEND_ON_PERIOD =
      noClasses()
          .that()
          .resideInAPackage("..domain.account..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..domain.period..");

  @ArchTest
  static final ArchRule PERIOD_DOES_NOT_DEPEND_ON_ACCOUNT =
      noClasses()
          .that()
          .resideInAPackage("..domain.period..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..domain.account..")
          .allowEmptyShould(true);
}
