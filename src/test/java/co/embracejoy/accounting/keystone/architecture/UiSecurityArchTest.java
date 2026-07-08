package co.embracejoy.accounting.keystone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.annotation.Profile;

/**
 * ArchUnit rules enforcing ADR-0019 (OAuth2 client + session cookie for the admin UI) and ADR-0020
 * (embedded Spring Authorization Server, dev/test only).
 */
@AnalyzeClasses(
    packages = "co.embracejoy.accounting.keystone",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class UiSecurityArchTest {

  @ArchTest
  static final ArchRule OAUTH2LOGIN_ONLY_IN_UI_SECURITY_CONFIG =
      noClasses()
          .that()
          .resideInAPackage("co.embracejoy.accounting.keystone..")
          .and()
          .haveSimpleNameNotEndingWith("UiSecurityConfig")
          .should()
          .callMethodWhere(JavaCall.Predicates.target(HasName.Predicates.name("oauth2Login")))
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule SAS_CONFIG_IS_GUARDED_ON_DEV_TEST =
      classes()
          .that()
          .haveSimpleName("EmbeddedAuthorizationServerConfig")
          .should()
          .beAnnotatedWith(profileWithDevAndTest());

  private static DescribedPredicate<JavaAnnotation<?>> profileWithDevAndTest() {
    return new DescribedPredicate<>("@Profile with value including {\"dev\", \"test\"}") {
      @Override
      public boolean test(JavaAnnotation<?> annotation) {
        if (!annotation.getRawType().getName().equals(Profile.class.getName())) {
          return false;
        }
        return annotation
            .get("value")
            .filter(Object[].class::isInstance)
            .map(Object[].class::cast)
            .map(
                profiles -> {
                  Set<String> set = new HashSet<>();
                  for (Object profile : profiles) {
                    set.add(profile.toString());
                  }
                  return set.contains("dev") && set.contains("test");
                })
            .orElse(false);
      }
    };
  }
}
