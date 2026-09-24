package com.loresentry.authentication;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final String ROOT = "com.loresentry.authentication";
    private static final ArchRule DOMAIN =
            classes()
                    .that()
                    .resideInAPackage(ROOT + ".domain..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", ROOT + ".domain..");
    private static final ArchRule PORTS =
            classes()
                    .that()
                    .resideInAPackage(ROOT + ".application.port..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", ROOT + ".domain..", ROOT + ".application.port..");

    @Test
    void productionDependenciesPointTowardCore() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(ROOT);
        DOMAIN.check(classes);
        PORTS.check(classes);
        classes()
                .that()
                .resideInAPackage(ROOT + ".application.service..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        ROOT + ".domain..",
                        ROOT + ".application.port..",
                        ROOT + ".application.service..")
                .allowEmptyShould(true)
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".adapter..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".config..", ROOT + ".application.service..")
                .check(classes);
    }

    @Test
    void ruleRejectsFrameworkDependency() {
        var fixture =
                new ClassFileImporter()
                        .importClasses(com.loresentry.authentication.archfixture.InvalidCore.class);
        var rule =
                classes()
                        .should()
                        .onlyDependOnClassesThat()
                        .resideInAnyPackage("java..", ROOT + ".archfixture..");
        assertThat(rule.evaluate(fixture).hasViolation()).isTrue();
    }
}
