package com.loresentry.authentication.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loresentry.authentication.support.Slf4jLogSinkExtension;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * AppLogger가 줄마다 찍은 위치(log.layer·log.package·log.origin.function)와 문맥 태그를 넣는지 확인한다(LOG_SCHEMA.md
 * 5절). 레이어는 @LogLayer, 클래스 이름 끝 규칙, 바깥 클래스 순서로 정한다. 바인딩하는 동안 문맥 필드와 흐름 중간에 알린 사용자가 MDC에 있어 프레임워크
 * 줄에도 붙는지도 본다.
 *
 * <p>AppLogger는 순수 Java이고 출력은 adapter의 SLF4J sink가 맡는다. Spring 없이 도는 테스트라 확장이 그 sink를 꽂고, 줄이
 * Logback까지 가는지 이 테스트가 함께 확인한다.
 */
@ExtendWith(Slf4jLogSinkExtension.class)
class AppLoggerLocationTest {
    private final ListAppender<ILoggingEvent> events =
            new ListAppender<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    // 줄을 찍은 순간의 MDC를 담아 둔다(Logback은 MDC를 처음 읽을 때 복사하므로, 나중에 읽으면 그때의 MDC가 나온다)
                    event.prepareForDeferredProcessing();
                    super.append(event);
                }
            };
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void attach() {
        events.start();
        root.addAppender(events);
    }

    @AfterEach
    void detach() {
        root.detachAppender(events);
    }

    private Map<String, Object> pairs() {
        ILoggingEvent last = events.list.getLast();
        Map<String, Object> out = new HashMap<>();
        last.getKeyValuePairs().forEach(p -> out.put(p.key, p.value));
        return out;
    }

    static final class SampleController {
        void get() {
            AppLogger.current().info("sample get requested");
        }
    }

    static final class SampleService {
        void get() {
            AppLogger.current().info("sample get");
        }
    }

    static final class SampleListener {
        void receive() {
            AppLogger.current().info("sample event received");
        }
    }

    static final class SamplePublisher {
        void publish() {
            AppLogger.current().info("sample publish");
        }
    }

    static final class SampleStore {
        void read() {
            AppLogger.current().info("sample read");
        }
    }

    @LogLayer("scheduler")
    static final class SamplePurge {
        void run() {
            AppLogger.current().info("sample purge start");
        }
    }

    static final class Helper {
        void run() {
            AppLogger.current().info("helper");
        }
    }

    static final class SampleRepository {
        // 람다 안에서 찍어도 바깥 클래스의 레이어와 람다를 감싼 메서드 이름이 나와야 한다
        void read() {
            Supplier<String> read =
                    () -> {
                        AppLogger.current().info("sample read");
                        return "x";
                    };
            read.get();
        }
    }

    @Test
    void layerComesFromTheClassNameSuffix() {
        new SampleController().get();
        assertThat(pairs())
                .containsEntry("log.layer", "controller")
                .containsEntry("log.origin.function", "get")
                .containsEntry("log.package", "com.loresentry.authentication.logging");
        new SampleService().get();
        assertThat(pairs()).containsEntry("log.layer", "service");
        new SampleListener().receive();
        assertThat(pairs()).containsEntry("log.layer", "consumer");
        new SamplePublisher().publish();
        assertThat(pairs()).containsEntry("log.layer", "publisher");
        new SampleStore().read();
        assertThat(pairs()).containsEntry("log.layer", "repository");
    }

    @Test
    void lambdasReportTheEnclosingMethodAndClass() {
        new SampleRepository().read();
        assertThat(pairs())
                .containsEntry("log.origin.function", "read")
                .containsEntry("log.layer", "repository");
        assertThat(events.list.getLast().getLoggerName())
                .endsWith("AppLoggerLocationTest$SampleRepository");
    }

    @Test
    void scheduledJobsAreNamedByAnnotationAndUnknownClassesAreOther() {
        new SamplePurge().run();
        assertThat(pairs()).containsEntry("log.layer", "scheduler");
        new Helper().run();
        assertThat(pairs()).containsEntry("log.layer", "other");
    }

    @Test
    void contextTagsBecomeMarkersAndTheBindingIsRestored() {
        try (var bound =
                AppLogger.bind(
                        new AppLogger("a".repeat(32), "b".repeat(16), null).withTag("health"))) {
            new SampleService().get();
            assertThat(AppLogger.current().traceId()).isEqualTo("a".repeat(32));
        }
        assertThat(events.list.getLast().getMarkerList())
                .extracting(org.slf4j.Marker::getName)
                .containsExactly("health");
        assertThat(AppLogger.current().traceId()).isNull();
    }

    @Test
    void contextFieldsStayInMdcWhileBoundSoFrameworkLinesGetThem() {
        // 바인딩한 동안 같은 스레드에서 프레임워크가 찍는 줄(여기서는 SLF4J logger를 직접 쓴 줄)의 MDC에 흐름의 문맥 필드가 있다.
        // 예: 요청 하나의 경로·메서드, 스케줄 회차 하나의 작업 이름, outbox 행 하나의 이벤트 값
        org.slf4j.Logger framework =
                LoggerFactory.getLogger("org.springframework.sample.Framework");
        UUID event = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        AppLogger row =
                new AppLogger("a".repeat(32), "b".repeat(16), null)
                        .with(
                                LogField.httpMethod("POST"),
                                LogField.urlPath("/auth/terms/accept"),
                                LogField.route("/auth/terms/accept"),
                                LogField.scheduleName("outbox-relay"),
                                LogField.cloudeventId(event),
                                LogField.cloudeventType("UserDeleted"),
                                LogField.cloudeventSubject(subject),
                                LogField.param("terms_version_id", UUID.randomUUID()));
        try (var bound = AppLogger.bind(row)) {
            framework.warn("framework line");
            assertThat(events.list.getLast().getMDCPropertyMap())
                    .containsEntry("http.request.method", "POST")
                    .containsEntry("url.path", "/auth/terms/accept")
                    .containsEntry("http.route", "/auth/terms/accept")
                    .containsEntry("loresentry.schedule.name", "outbox-relay")
                    .containsEntry("loresentry.cloudevent.id", event.toString())
                    .containsEntry("loresentry.cloudevent.type", "UserDeleted")
                    .containsEntry("loresentry.cloudevent.subject", subject.toString())
                    .containsEntry("trace.id", "a".repeat(32))
                    // MDC_CONTEXT_KEYS 밖의 문맥 필드는 MDC에 넣지 않는다(우리 줄에만 붙는다)
                    .doesNotContainKey("loresentry.param.terms_version_id");
            // 안쪽에서 문맥 필드가 없는 객체를 바인딩하면 MDC는 그 객체의 값만 갖고, 닫으면 바깥 흐름의 값이 돌아온다
            try (var inner = AppLogger.bind(new AppLogger(null, null, null))) {
                framework.warn("inner line");
                assertThat(events.list.getLast().getMDCPropertyMap())
                        .doesNotContainKeys("url.path", "loresentry.cloudevent.id", "trace.id");
            }
            framework.warn("outer again");
            assertThat(events.list.getLast().getMDCPropertyMap())
                    .containsEntry("url.path", "/auth/terms/accept");
        }
        // 바인딩이 끝나면 MDC에서 빠진다(요청·스케줄 스레드를 다시 쓰는 다음 흐름에 남지 않는다)
        framework.warn("after flow");
        assertThat(events.list.getLast().getMDCPropertyMap())
                .doesNotContainKeys(
                        "http.request.method",
                        "url.path",
                        "loresentry.schedule.name",
                        "loresentry.cloudevent.id",
                        "trace.id");
    }

    @Test
    void anIdentifiedUserIsOnLaterLinesAndSurvivesAnInnerBinding() {
        // 흐름 중간에 알게 된 사용자(identify)는 그 뒤의 우리 줄과 프레임워크 줄에 붙는다. 같은 흐름의 안쪽 바인딩이 닫혀도 남는다
        org.slf4j.Logger framework =
                LoggerFactory.getLogger("org.springframework.sample.Framework");
        UUID user = UUID.randomUUID();
        AppLogger flow = new AppLogger("c".repeat(32), "d".repeat(16), null);
        try (var bound = AppLogger.bind(flow)) {
            new SampleService().get();
            assertThat(events.list.getLast().getMDCPropertyMap()).doesNotContainKey("user.id");
            try (var inner = AppLogger.bind(flow.with(LogField.scheduleName("sample")))) {
                AppLogger.current().identify(user);
                new SampleStore().read();
                assertThat(events.list.getLast().getMDCPropertyMap())
                        .containsEntry("user.id", user.toString());
            }
            // 안쪽 바인딩을 닫아도 바깥 흐름의 사용자로 남는다(같은 흐름의 공유 칸)
            framework.warn("framework line");
            assertThat(events.list.getLast().getMDCPropertyMap())
                    .containsEntry("user.id", user.toString());
            new SampleService().get();
            assertThat(events.list.getLast().getMDCPropertyMap())
                    .containsEntry("user.id", user.toString());
        }
        // 흐름이 끝나면 사용자가 MDC에 남지 않는다
        assertThat(org.slf4j.MDC.get("user.id")).isNull();
        // 바인딩이 없는 기본 객체에는 사용자를 알려도 아무 일도 없다
        AppLogger.current().identify(user);
        assertThat(org.slf4j.MDC.get("user.id")).isNull();
    }
}
