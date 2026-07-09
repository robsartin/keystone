package co.embracejoy.accounting.keystone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces ADR-0008 (structured JSON logs + MDC correlation IDs): no production code calls {@code
 * System.out}, {@code System.err}, or {@code Throwable.printStackTrace()}. Those bypass Logback,
 * lose MDC context, and produce log lines with no structure — defeating the observability contract.
 *
 * <p>Use SLF4J (via the LOGGER convention already in use across the codebase) instead.
 */
@AnalyzeClasses(
    packages = "co.embracejoy.accounting.keystone",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class NoStandardOutInProductionArchTest {

  @ArchTest
  static final ArchRule PRODUCTION_DOES_NOT_ACCESS_SYSTEM_OUT =
      noClasses()
          .should()
          .accessField(System.class, "out")
          .because("use SLF4J instead of System.out (ADR-0008)");

  @ArchTest
  static final ArchRule PRODUCTION_DOES_NOT_ACCESS_SYSTEM_ERR =
      noClasses()
          .should()
          .accessField(System.class, "err")
          .because("use SLF4J instead of System.err (ADR-0008)");

  @ArchTest
  static final ArchRule PRODUCTION_DOES_NOT_CALL_PRINT_STACK_TRACE =
      noClasses()
          .should()
          .callMethod(Throwable.class, "printStackTrace")
          .because("log via SLF4J with the throwable as the last argument (ADR-0008)");
}
