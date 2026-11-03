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

    /**
     * service가 쓰는 로그 클래스도 core처럼 라이브러리를 모른다(LOG_SCHEMA.md 5절). SLF4J로 실제로 출력하는 구현은
     * adapter.out.logging의 LogSink 구현이고, config가 앱이 시작할 때 AppLogger에 꽂는다.
     */
    private static final ArchRule LOGGING =
            classes()
                    .that()
                    .resideInAPackage(ROOT + ".logging..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", ROOT + ".logging..");

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
                        ROOT + ".application.service..",
                        // 로그 클래스(AppLogger·LogField 등). 아래 LOGGING 규칙이 순수 Java인지 따로 본다
                        ROOT + ".logging..")
                .allowEmptyShould(true)
                .check(classes);
        LOGGING.check(classes);
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
