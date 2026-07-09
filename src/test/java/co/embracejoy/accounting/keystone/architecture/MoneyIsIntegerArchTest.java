package co.embracejoy.accounting.keystone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.math.BigDecimal;

/**
 * Enforces ADR-0003 (money as integer minor units): no floating-point or {@link BigDecimal} fields
 * anywhere in the domain layer. Money is always {@code long} minor units; ISO 4217 via {@link
 * java.util.Currency}.
 *
 * <p>Domain-only scope: infrastructure adapters may legitimately convert to/from other numeric
 * types at the boundary (e.g. Postgres numeric columns), but the domain must never accept or store
 * them.
 */
@AnalyzeClasses(
    packages = "co.embracejoy.accounting.keystone",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class MoneyIsIntegerArchTest {

  @ArchTest
  static final ArchRule DOMAIN_HAS_NO_DOUBLE_FIELDS =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("..domain..")
          .should()
          .haveRawType(double.class);

  @ArchTest
  static final ArchRule DOMAIN_HAS_NO_FLOAT_FIELDS =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("..domain..")
          .should()
          .haveRawType(float.class);

  @ArchTest
  static final ArchRule DOMAIN_HAS_NO_BOXED_DOUBLE_FIELDS =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("..domain..")
          .should()
          .haveRawType(Double.class);

  @ArchTest
  static final ArchRule DOMAIN_HAS_NO_BOXED_FLOAT_FIELDS =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("..domain..")
          .should()
          .haveRawType(Float.class);

  @ArchTest
  static final ArchRule DOMAIN_HAS_NO_BIG_DECIMAL_FIELDS =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("..domain..")
          .should()
          .haveRawType(BigDecimal.class);
}
