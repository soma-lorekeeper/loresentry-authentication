package com.loresentry.authentication;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogLayer;
import com.loresentry.authentication.logging.SkipEntryLog;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 로그 규칙을 테스트로 강제한다(LOG_SCHEMA.md 5절). 테스트 코드는 검사하지 않는다. gateway·content의 같은 이름 테스트와 같은 규칙이다.
 *
 * <ul>
 *   <li>로그는 AppLogger로만 찍는다. 로깅 라이브러리는 SLF4J 출력 구현이 있는 adapter.out.logging만 쓴다. 표준 출력은 아무도 쓰지 않는다.
 *   <li>레이어 클래스의 private이 아닌 모든 메서드(static 제외)는 AppLogger의 info·warn·error를 직접 부른다. 그래서 trace 하나를
 *       시간 순으로 놓으면 controller → service → store 흐름이 줄 단위로 보인다. 이유를 적은 {@link SkipEntryLog}가 붙은 메서드만
 *       뺀다.
 * </ul>
 */
class LoggingArchitectureTest {
    private static final String ROOT = "com.loresentry.authentication";

    /** 진입 로그를 남겨야 하는 레이어 클래스의 이름 끝(LOG_SCHEMA.md 5절 레이어 표의 업무 레이어). */
    private static final List<String> LAYER_SUFFIXES =
            List.of(
                    "Controller",
                    "Service",
                    "Client",
                    "Repository",
                    "Store",
                    "Listener",
                    "Consumer",
                    "Publisher");

    private static final Set<String> LOG_METHODS = Set.of("info", "warn", "error");

    private static final JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages(ROOT);

    @Test
    void onlyTheLogSinkUsesLoggingLibraries() {
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".adapter.out.logging..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.slf4j..",
                        "ch.qos.logback..",
                        "java.util.logging..",
                        "org.apache.commons.logging..",
                        "org.apache.logging.log4j..")
                .because("로그는 com.loresentry.authentication.logging.AppLogger로만 찍는다")
                .check(CLASSES);
    }

    @Test
    void nothingWritesToStandardStreams() {
        noClasses()
                .should(GeneralCodingRules.ACCESS_STANDARD_STREAMS)
                .because("System.out·System.err 줄은 ECS JSON이 아니어서 Logstash가 필드로 읽지 못한다")
                .check(CLASSES);
    }

    @Test
    void everyLayerMethodLogs() {
        methods()
                .that()
                .areNotPrivate()
                .and()
                .areNotStatic()
                .and()
                .areDeclaredInClassesThat(layerClasses())
                .and()
                .areNotAnnotatedWith(SkipEntryLog.class)
                .should(callAppLogger())
                .because("레이어 클래스의 private이 아닌 모든 메서드는 첫 줄에 진입 로그를 남긴다(LOG_SCHEMA.md 5절)")
                .check(CLASSES);
    }

    /** 이름 끝이 업무 레이어이거나 @LogLayer가 붙은 일반 클래스. record·interface(port)·enum과 로그 패키지는 뺀다. */
    private static DescribedPredicate<JavaClass> layerClasses() {
        return DescribedPredicate.describe(
                "layer classes",
                type ->
                        !type.isRecord()
                                && !type.isInterface()
                                && !type.isEnum()
                                && !type.getPackageName().startsWith(ROOT + ".logging")
                                && !type.getPackageName().startsWith(ROOT + ".adapter.out.logging")
                                && (type.isAnnotatedWith(LogLayer.class)
                                        || LAYER_SUFFIXES.stream()
                                                .anyMatch(type.getSimpleName()::endsWith)));
    }

    /** 메서드 본문이 AppLogger의 info·warn·error를 직접 부르는지. 컴파일러가 만든 bridge·synthetic 메서드는 건너뛴다. */
    private static ArchCondition<JavaMethod> callAppLogger() {
        return new ArchCondition<>("call AppLogger.info, warn or error") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.getModifiers().contains(JavaModifier.BRIDGE)
                        || method.getModifiers().contains(JavaModifier.SYNTHETIC)) return;
                boolean logs =
                        method.getMethodCallsFromSelf().stream()
                                .anyMatch(
                                        call ->
                                                call.getTargetOwner()
                                                                .isEquivalentTo(AppLogger.class)
                                                        && LOG_METHODS.contains(call.getName()));
                if (!logs)
                    events.add(
                            SimpleConditionEvent.violated(
                                    method,
                                    method.getFullName() + " does not log through AppLogger"));
            }
        };
    }
}
