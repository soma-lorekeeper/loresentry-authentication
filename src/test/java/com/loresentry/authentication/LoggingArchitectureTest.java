package com.loresentry.authentication;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.loresentry.authentication.adapter.out.logging.LogFlow;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import com.loresentry.authentication.logging.LogLayer;
import com.loresentry.authentication.logging.SkipEntryLog;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
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
 *   <li>레이어 클래스의 private이 아닌 모든 메서드(static 제외)는 AppLogger의 info·warn·error를 직접 부르거나, 그 자리에 시작 줄을
 *       남기는 LogFlow 흐름으로 감싼다. 그래서 trace 하나를 시간 순으로 놓으면 controller → service → store 흐름이 줄 단위로 보인다.
 *       이유를 적은 {@link SkipEntryLog}가 붙은 메서드만 뺀다.
 *   <li>로그 객체를 만들고 바인딩하는 일, 시간을 재는 일, 시작·종료 줄을 쓰는 일은 로깅 팩토리만 한다. 로그 패키지가 둘(core logging,
 *       adapter.out.logging)이라 그 멤버가 public이어서, 두 패키지 밖에서는 부르지 못하게 여기서 막는다(LOG_SCHEMA.md 5절 "만들기").
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

    /**
     * 그 메서드 자리에 시작 줄을 남기는 LogFlow 흐름. 이 흐름으로 감싼 메서드는 진입 줄이 있는 것으로 본다(외부 호출 call은 결과 줄만 남기고,
     * polling은 할 일이 있을 때만 남기므로 뺀다). 이 서비스는 Kafka를 받지 않아 kafkaRecord가 없다.
     */
    private static final Set<String> FLOW_METHODS = Set.of("schedule", "task", "request");

    /**
     * 로깅 팩토리만 부르는 AppLogger 멤버. 객체 만들기·바인딩(생성자, with, withTag, withUser, bind)과 시작·종료 줄 쓰기(infoAt,
     * warnAt, errorAt, callerOf, beforeFirstLine, outcomeFields)다.
     */
    private static final Set<String> FACTORY_MEMBERS =
            Set.of(
                    "<init>",
                    "with",
                    "withTag",
                    "withUser",
                    "bind",
                    "infoAt",
                    "warnAt",
                    "errorAt",
                    "callerOf",
                    "beforeFirstLine",
                    "outcomeFields");

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
    void onlyTheLogSinkUsesTracingLibraries() {
        // span을 열고, 지금 문맥을 바꾸고, 스레드로 문맥을 옮기는 일은 로깅 팩토리(adapter.out.logging)만 한다. 서비스 코드는 할 일(람다)과
        // 값만
        // 넘긴다. traceparent 헤더도 코드가 넣지 않는다(Kafka 발행 계측이 지금 문맥으로 넣는다). LOG_SCHEMA.md 5절 "만들기", 7절
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".adapter.out.logging..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.opentelemetry..", "io.micrometer.tracing..", "io.micrometer.context..")
                .because("추적은 EDOT agent와 로깅 팩토리가 맡는다. 서비스 코드는 추적 API를 모른다")
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
    void onlyTheLoggingFactoriesBuildLoggersOrMeasureTime() {
        noClasses()
                .that()
                .resideOutsideOfPackages(ROOT + ".logging..", ROOT + ".adapter.out.logging..")
                .should()
                .callCodeUnitWhere(
                        DescribedPredicate.describe(
                                "a logging factory member (AppLogger constructor, with, withTag,"
                                        + " withUser, bind, infoAt, warnAt, errorAt, callerOf,"
                                        + " beforeFirstLine, outcomeFields, LogField.duration)",
                                LoggingArchitectureTest::factoryMember))
                .because("서비스 코드는 인자만 넘기고, 로그 객체·바인딩·걸린 시간·시작·종료 줄은 로깅 팩토리가 맡는다(LOG_SCHEMA.md 5절)")
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

    /** 로깅 팩토리만 부르는 멤버를 부르는 호출인지. */
    private static boolean factoryMember(JavaCall<?> call) {
        JavaClass owner = call.getTargetOwner();
        return (owner.isEquivalentTo(AppLogger.class) && FACTORY_MEMBERS.contains(call.getName()))
                || (owner.isEquivalentTo(LogField.class) && "duration".equals(call.getName()));
    }

    /**
     * 메서드 본문이 AppLogger의 info·warn·error를 직접 부르거나, 시작 줄을 남기는 LogFlow 흐름(schedule·task·request)으로
     * 감싸는지. 컴파일러가 만든 bridge·synthetic 메서드는 건너뛴다.
     */
    private static ArchCondition<JavaMethod> callAppLogger() {
        return new ArchCondition<>("call AppLogger.info, warn or error, or open a LogFlow") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.getModifiers().contains(JavaModifier.BRIDGE)
                        || method.getModifiers().contains(JavaModifier.SYNTHETIC)) return;
                boolean logs =
                        method.getMethodCallsFromSelf().stream()
                                .anyMatch(
                                        call ->
                                                (call.getTargetOwner()
                                                                        .isEquivalentTo(
                                                                                AppLogger.class)
                                                                && LOG_METHODS.contains(
                                                                        call.getName()))
                                                        || (call.getTargetOwner()
                                                                        .isEquivalentTo(
                                                                                LogFlow.class)
                                                                && FLOW_METHODS.contains(
                                                                        call.getName())));
                if (!logs)
                    events.add(
                            SimpleConditionEvent.violated(
                                    method,
                                    method.getFullName() + " does not log through AppLogger"));
            }
        };
    }
}
