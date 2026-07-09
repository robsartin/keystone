package co.embracejoy.accounting.keystone.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Enforces ADR-0015 (no URL versioning): no {@code @RequestMapping} / {@code @GetMapping} /
 * {@code @PostMapping} / {@code @PutMapping} / {@code @DeleteMapping} / {@code @PatchMapping}
 * annotation value matches {@code /v[0-9]+/}. Versioning lives in the OpenAPI contract (ADR-0006),
 * not in the URL — clients should never write {@code /v1/journal-entries}.
 */
@AnalyzeClasses(
    packages = "co.embracejoy.accounting.keystone",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class NoUrlVersioningArchTest {

  private static final Pattern URL_VERSION = Pattern.compile(".*/v[0-9]+(/.*)?$");

  private static final Set<Class<?>> MAPPING_ANNOTATIONS =
      Set.of(
          RequestMapping.class,
          GetMapping.class,
          PostMapping.class,
          PutMapping.class,
          DeleteMapping.class,
          PatchMapping.class);

  @ArchTest
  static final ArchRule NO_URL_VERSIONING_IN_MAPPINGS =
      classes().should(mappingAnnotationsHaveNoVersionPrefix()).allowEmptyShould(true);

  private static ArchCondition<JavaClass> mappingAnnotationsHaveNoVersionPrefix() {
    return new ArchCondition<>("declare @*Mapping value without a /v<N>/ URL-version prefix") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        checkAnnotationsOn(javaClass, javaClass.getAnnotations(), events);
        javaClass
            .getMethods()
            .forEach(m -> checkAnnotationsOn(javaClass, m.getAnnotations(), events));
      }
    };
  }

  private static void checkAnnotationsOn(
      JavaClass owner, Set<? extends JavaAnnotation<?>> annotations, ConditionEvents events) {
    for (JavaAnnotation<?> annotation : annotations) {
      if (!isMappingAnnotation(annotation)) {
        continue;
      }
      Object rawValue = annotation.get("value").orElse(null);
      if (rawValue == null) {
        continue;
      }
      Object[] values = rawValue instanceof Object[] arr ? arr : new Object[] {rawValue};
      for (Object v : values) {
        String path = v == null ? "" : v.toString();
        if (URL_VERSION.matcher(path).matches()) {
          events.add(
              SimpleConditionEvent.violated(
                  owner,
                  owner.getFullName()
                      + " has "
                      + annotation.getRawType().getSimpleName()
                      + "(\""
                      + path
                      + "\") — versioning belongs in the OpenAPI contract, not the URL (ADR-0015)."));
        }
      }
    }
  }

  private static boolean isMappingAnnotation(JavaAnnotation<?> annotation) {
    String name = annotation.getRawType().getName();
    return MAPPING_ANNOTATIONS.stream().anyMatch(c -> c.getName().equals(name));
  }
}
