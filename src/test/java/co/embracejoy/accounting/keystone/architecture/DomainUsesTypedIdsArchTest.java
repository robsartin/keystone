package co.embracejoy.accounting.keystone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.UUID;

/**
 * Enforces ADR-0010 (typed ID wrappers): domain classes must not hold raw {@link UUID} fields.
 * Every identity is a typed record (e.g. {@code JournalEntryId}, {@code TenantId}) so signatures
 * stay unambiguous — a method taking {@code (UUID, UUID)} can silently swap arguments; {@code
 * (JournalEntryId, TenantId)} cannot.
 *
 * <p>The typed ID records themselves ({@code JournalEntryId}, {@code TenantId}) are exempted: they
 * exist precisely to wrap the raw {@link UUID}.
 */
@AnalyzeClasses(
    packages = "co.embracejoy.accounting.keystone",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class DomainUsesTypedIdsArchTest {

  @ArchTest
  static final ArchRule DOMAIN_HAS_NO_RAW_UUID_FIELDS =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("..domain..")
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleNameNotEndingWith("Id")
          .should()
          .haveRawType(UUID.class);
}
