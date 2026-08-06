package com.medassist.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {
  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.medassist");

  @Test
  void sharedModulesDoNotDependOnServices() {
    noClasses()
        .that()
        .resideInAnyPackage("com.medassist.domain..", "com.medassist.common..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(servicePackages())
        .check(classes);
  }

  @Test
  void servicesDoNotDependOnOtherServicesDirectly() {
    final String[] packages = servicePackages();
    for (final String servicePackage : packages) {
      noClasses()
          .that()
          .resideInAPackage(servicePackage)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(otherPackages(servicePackage, packages))
          .check(classes);
    }
  }

  @Test
  void domainModelDoesNotDependOnSpringOrJpa() {
    classes()
        .that()
        .resideInAPackage("com.medassist.domain..")
        .should()
        .onlyDependOnClassesThat()
        .resideOutsideOfPackages("org.springframework..", "jakarta.persistence..", "javax.persistence..")
        .check(classes);
  }

  @Test
  void domainCollectionsAreFinalByConstruction() {
    fields()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("com.medassist.domain..")
        .and()
        .haveRawType(java.util.Collection.class)
        .should()
        .beFinal()
        .check(classes);
  }

  @Test
  void controllersDoNotDependOnRepositoriesDirectly() {
    noClasses()
        .that()
        .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..repository..")
        .check(classes);
  }

  private static String[] servicePackages() {
    return new String[] {
      "com.medassist.gateway..",
      "com.medassist.identitypolicy..",
      "com.medassist.ingestion..",
      "com.medassist.clinicaldata..",
      "com.medassist.retrieval..",
      "com.medassist.agent..",
      "com.medassist.auditgovernance.."
    };
  }

  private static String[] otherPackages(final String servicePackage, final String[] packages) {
    final java.util.List<String> others = new java.util.ArrayList<>();
    for (final String candidate : packages) {
      if (!candidate.equals(servicePackage)) {
        others.add(candidate);
      }
    }
    return others.toArray(String[]::new);
  }
}
