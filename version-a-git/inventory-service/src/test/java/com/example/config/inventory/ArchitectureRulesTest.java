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
 * Build-time guards. The first group catches mistakes that silently break live refresh: each
 * compiles, starts and serves the first value correctly - and then never picks up a change. The
 * second keeps the package layering ({@code controller -> config, dto}) and coding standards.
 */
@AnalyzeClasses(
    packages = "com.example.config.inventory",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

  /** A refresh updates the existing object through setters; a record cannot be updated. */
  @ArchTest
  static final ArchRule configuration_properties_must_not_be_records =
      classes()
          .that()
          .areAnnotatedWith(ConfigurationProperties.class)
          .should(notBeRecords())
          .because("a refresh updates the existing instance in place, which a record forbids");

  @ArchTest
  static final ArchRule configuration_properties_must_have_setters =
      classes()
          .that()
          .areAnnotatedWith(ConfigurationProperties.class)
          .should(haveASetterForEveryMutableField())
          .because("a refresh writes new values through the setters");

  /** {@code @Value} is resolved once at startup and never changes afterwards. */
  @ArchTest
  static final ArchRule no_value_annotated_fields =
      noFields()
          .should()
          .beAnnotatedWith(Value.class)
          .because("@Value fields never refresh; use a @ConfigurationProperties class");

  @ArchTest
  static final ArchRule configuration_properties_live_in_config =
      classes()
          .that()
          .areAnnotatedWith(ConfigurationProperties.class)
          .should()
          .resideInAPackage("..config..");

  @ArchTest
  static final ArchRule nothing_depends_on_controllers =
      noClasses()
          .that()
          .resideOutsideOfPackage("..controller..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..controller..");

  /** Response bodies are plain data: no Spring types leak into the API contract. */
  @ArchTest
  static final ArchRule dtos_do_not_depend_on_spring =
      noClasses()
          .that()
          .resideInAPackage("..dto..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..");

  @ArchTest
  static final ArchRule no_field_injection =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

  @ArchTest
  static final ArchRule no_standard_streams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule no_generic_exceptions =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  @ArchTest
  static final ArchRule no_java_util_logging =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  private static ArchCondition<JavaClass> notBeRecords() {
    return new ArchCondition<>("not be a record") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        boolean isRecord =
            item.getRawSuperclass()
                .map(superclass -> "java.lang.Record".equals(superclass.getName()))
                .orElse(false);
        if (isRecord) {
          events.add(SimpleConditionEvent.violated(item, item.getName() + " is a record"));
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
                    item, item.getName() + " has no " + expected + "(...)"));
          }
        }
      }
    };
  }
}
