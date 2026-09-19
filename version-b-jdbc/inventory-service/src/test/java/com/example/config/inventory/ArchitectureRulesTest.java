package com.example.config.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Executable enforcement of the design constraints this project depends on.
 *
 * <p>Three of these rules exist because the corresponding mistake is silent at compile time, silent
 * at startup, and only shows up as configuration that mysteriously fails to refresh in production.
 * A code review will not reliably catch them; the build will.
 */
@AnalyzeClasses(
    packages = "com.example.config.inventory",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

  // ===================================================================================
  // The rules that protect live refresh
  // ===================================================================================

  /**
   * Spring Cloud's rebinder refreshes a properties bean by re-initialising the EXISTING instance
   * through its setters. A record is re-created instead, so every reference injected before the
   * refresh keeps stale values.
   */
  @ArchTest
  static final ArchRule refreshable_configuration_properties_must_not_be_records =
      classes()
          .that()
          .areAnnotatedWith(ConfigurationProperties.class)
          .should(notBeRecords())
          .because(
              "the rebinder mutates the existing instance via setters; a record is re-created, "
                  + "so previously injected references would keep stale values and live refresh "
                  + "would silently stop working");

  /** Constructor binding has the same problem as a record: nothing to mutate in place. */
  @ArchTest
  static final ArchRule refreshable_configuration_properties_must_expose_setters =
      classes()
          .that()
          .areAnnotatedWith(ConfigurationProperties.class)
          .should(haveASetterForEveryMutableField())
          .because(
              "setter-based JavaBean binding is what allows ConfigurationPropertiesRebinder to "
                  + "refresh the bean in place");

  /**
   * {@code ConfigurationPropertiesRebinder.rebind} rethrows after recording the error, so a
   * constraint violation during rebind propagates out of {@code ContextRefresher.refresh()} and
   * {@code RefreshScopeRefreshedEvent} is never published — losing last-known-good entirely.
   */
  @ArchTest
  static final ArchRule refreshable_configuration_properties_must_not_be_validated =
      classes()
          .that()
          .areAnnotatedWith(ConfigurationProperties.class)
          .should(notBeAnnotatedWithValidated())
          .because(
              "the rebinder rethrows on violation, which aborts the refresh chain before "
                  + "RefreshScopeRefreshedEvent is published; validation belongs in the "
                  + "snapshot provider so a bad value can be rejected observably");

  /**
   * A {@code @Value}-injected field in a plain singleton is resolved once at construction and never
   * updates, which looks exactly like a broken refresh.
   */
  @ArchTest
  static final ArchRule no_value_annotated_fields =
      noFields()
          .should()
          .beAnnotatedWith(Value.class)
          .because(
              "@Value fields never refresh; refreshable configuration must be read through a "
                  + "ConfigurationSnapshotProvider");

  /**
   * Business code must read an immutable snapshot, never the mutable properties bean, which the
   * rebinder mutates field by field while requests are in flight.
   */
  @ArchTest
  static final ArchRule services_must_not_read_configuration_properties_directly =
      noClasses()
          .that()
          .resideInAPackage("..service..")
          .or()
          .resideInAPackage("..controller..")
          .should()
          .dependOnClassesThat()
          .areAnnotatedWith(ConfigurationProperties.class)
          .because(
              "a concurrent reader of a properties bean can observe half-applied state; business "
                  + "code must go through the snapshot provider");

  // ===================================================================================
  // General standards
  // ===================================================================================

  /** Constructor injection only: field injection hides dependencies and breaks plain unit tests. */
  @ArchTest
  static final ArchRule no_field_injection =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.because(
          "constructor injection keeps dependencies explicit and testable without a container");

  @ArchTest
  static final ArchRule domain_must_not_depend_on_spring =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "jakarta.persistence..")
          .because("domain snapshots are plain immutable values with no framework coupling");

  @ArchTest
  static final ArchRule controllers_must_not_be_called_by_services =
      noClasses()
          .that()
          .resideInAPackage("..service..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..controller..")
          .because("dependencies point controller -> service, never the reverse");

  @ArchTest
  static final ArchRule no_standard_streams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.because(
          "use SLF4J so output is structured and level-controlled");

  @ArchTest
  static final ArchRule no_generic_exceptions =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  @ArchTest
  static final ArchRule no_java_util_logging =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  // ===================================================================================
  // Custom conditions
  // ===================================================================================

  private static ArchCondition<JavaClass> notBeRecords() {
    return new ArchCondition<>("not be a record") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        boolean isRecord =
            item.getRawSuperclass()
                .map(superclass -> "java.lang.Record".equals(superclass.getName()))
                .orElse(false);
        if (isRecord) {
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getName() + " is a record and therefore cannot be rebound in place"));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> notBeAnnotatedWithValidated() {
    return new ArchCondition<>("not be annotated with @Validated") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        boolean validated =
            item.getAnnotations().stream()
                .anyMatch(a -> a.getRawType().getName().endsWith(".Validated"));
        if (validated) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getName()
                      + " is @Validated; a violation during rebind aborts the refresh chain"));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> haveASetterForEveryMutableField() {
    return new ArchCondition<>("have a setter for every mutable instance field") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (JavaField field : item.getFields()) {
          if (field.getModifiers().contains(JavaModifier.STATIC)
              || field.getModifiers().contains(JavaModifier.FINAL)) {
            continue;
          }
          String expected =
              "set"
                  + field.getName().substring(0, 1).toUpperCase(Locale.ROOT)
                  + field.getName().substring(1);
          boolean hasSetter =
              item.getMethods().stream().map(JavaMethod::getName).anyMatch(expected::equals);
          if (!hasSetter) {
            events.add(
                SimpleConditionEvent.violated(
                    item,
                    item.getName()
                        + " has no "
                        + expected
                        + "(...) so field '"
                        + field.getName()
                        + "' cannot be rebound on refresh"));
          }
        }
      }
    };
  }
}
