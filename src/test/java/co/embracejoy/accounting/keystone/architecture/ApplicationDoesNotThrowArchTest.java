package co.embracejoy.accounting.keystone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Enforces ADR-0004 (Result over exceptions, strict form): no public method in {@code
 * ..application..} declares a checked exception via {@code throws}. Application services expose
 * domain outcomes as {@code Result<T, E>} — thrown exceptions are reserved for true bugs (NPE,
 * illegal state) and must never appear in a public signature.
 *
 * <p>Extends {@code HexagonalArchitectureTest.NO_PUBLIC_METHOD_RETURNS_THROWABLE} from a
 * return-type proxy check to a direct {@code throws}-clause check on the application layer.
 */
@AnalyzeClasses(
    packages = "co.embracejoy.accounting.keystone",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ApplicationDoesNotThrowArchTest {

  @ArchTest
  static final ArchRule APPLICATION_PUBLIC_METHODS_DO_NOT_DECLARE_THROWS =
      noMethods()
          .that()
          .arePublic()
          .and()
          .areDeclaredInClassesThat()
          .resideInAPackage("..application..")
          .should(declareNoThrows());

  private static ArchCondition<JavaMethod> declareNoThrows() {
    return new ArchCondition<>("declare no throws clause") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        if (!method.getExceptionTypes().isEmpty()) {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  method.getFullName()
                      + " declares throws "
                      + method.getExceptionTypes()
                      + " — application services must return Result<T, E> instead."));
        }
      }
    };
  }
}
