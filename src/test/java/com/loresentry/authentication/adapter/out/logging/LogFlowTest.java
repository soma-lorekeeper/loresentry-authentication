package com.loresentry.authentication.adapter.out.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import com.loresentry.authentication.logging.LogLayer;
import com.loresentry.authentication.support.Slf4jLogSinkExtension;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 로깅 팩토리 {@link LogFlow}·{@link LogScope}와 {@link AppLogger#identify}가 흐름의 시작·종료 줄을 규칙대로 남기는지
 * 확인한다(LOG_SCHEMA.md 5절 "만들기", "접근 로그", "Kafka 레코드·스케줄 작업의 시작·종료 줄"). content의 같은 이름 테스트와 같은 규칙이고,
 * 이 서비스에만 있는 요청 흐름({@link LogFlow#request})을 더 본다.
 *
 * <ul>
 *   <li>시작·종료 줄이 짝을 이루고, 할 일이 예외로 끝나도 종료(실패) 줄이 남는다
 *   <li>폴링은 줄이 없는 회차에 아무 줄도 남기지 않고, 줄이 있는 회차에 시작·종료 줄을 남긴다
 *   <li>종료 줄과 외부 호출 결과 줄에 걸린 시간(event.duration, 나노초 숫자)과 결과 값이 붙는다
 *   <li>시작·종료 줄의 위치는 흐름을 연 메서드다(log.layer·log.origin.function)
 *   <li>요청 흐름은 응답 상태로 레벨을 정하고, 처리되지 않은 예외(java.lang.Error 포함)에도 종료 줄을 남긴다
 *   <li>진행 중에 알게 된 사용자(identify)가 이후 줄과 종료 줄에 붙는다
 * </ul>
 */
@ExtendWith(Slf4jLogSinkExtension.class)
class LogFlowTest {
    /** 테스트를 도는 스레드. 같은 JVM의 다른 테스트가 띄운 앱(Kafka client 등)이 다른 스레드에서 찍는 줄은 모으지 않는다. */
    private Thread testThread;

    private final ListAppender<ILoggingEvent> events =
            new ListAppender<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    if (Thread.currentThread() != testThread) return;
                    // 줄을 찍은 순간의 MDC를 담아 둔다(Logback은 MDC를 처음 읽을 때 복사하므로, 나중에 읽으면 그때의 MDC가 나온다)
                    event.prepareForDeferredProcessing();
                    super.append(event);
                }
            };
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void attach() {
        testThread = Thread.currentThread();
        events.start();
        root.addAppender(events);
    }

    @AfterEach
    void detach() {
        root.detachAppender(events);
    }

    /** 한 줄의 필드(key-value). 같은 이름은 먼저 넣은 값이 남는다(EcsLogFormatter와 같다). */
    private static Map<String, Object> pairs(ILoggingEvent event) {
        Map<String, Object> out = new HashMap<>();
        if (event.getKeyValuePairs() != null)
            event.getKeyValuePairs().forEach(p -> out.putIfAbsent(p.key, p.value));
        return out;
    }

    private List<String> messages() {
        return events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ILoggingEvent line(String message) {
        return events.list.stream()
                .filter(e -> message.equals(e.getFormattedMessage()))
                .findFirst()
                .orElseThrow();
    }

    /** 스케줄 작업 흉내. scheduler 레이어로 지정해, 시작·종료 줄이 이 클래스 자리에 남는지 본다. */
    @LogLayer("scheduler")
    static final class SamplePurge {
        void run() {
            LogFlow.schedule(
                    "sample-purge",
                    run -> {
                        AppLogger.current().info("sample rows delete");
                        run.result(LogField.result("deleted", 3));
                    },
                    LogField.param("retention_days", 7));
        }

        void fail() {
            LogFlow.schedule(
                    "sample-purge",
                    run -> {
                        throw new IllegalStateException("secret sql in message");
                    });
        }
    }

    /** 요청 필터 흉내. 이름 끝 규칙으로 filter 레이어다. 받은 상태로 끝나는 요청 하나를 연다. */
    static final class SampleFilter {
        void handle(String path, int status, UUID gatewayUser, UUID identified)
                throws IOException, ServletException {
            LogFlow.request(
                    path,
                    "/auth/sample/{id}",
                    "POST",
                    gatewayUser,
                    flow -> {
                        AppLogger.current().info("sample handle");
                        if (identified != null) AppLogger.current().identify(identified);
                        if (status >= 400)
                            AppLogger.current().addToOutcome(LogField.errorType("SAMPLE_FAILED"));
                        flow.status(status);
                    });
        }
    }

    @Test
    void aScheduleWritesStartAndEndLinesAtTheSchedulerMethod() {
        new SamplePurge().run();
        assertThat(messages())
                .containsExactly(
                        "schedule run start", "sample rows delete", "schedule run completed");
        Map<String, Object> start = pairs(line("schedule run start"));
        assertThat(start)
                .containsEntry("loresentry.schedule.name", "sample-purge")
                .containsEntry("loresentry.param.retention_days", 7)
                .containsEntry("log.layer", "scheduler")
                .containsEntry("log.origin.function", "run");
        assertThat(line("schedule run start").getLoggerName()).endsWith("LogFlowTest$SamplePurge");
        Map<String, Object> end = pairs(line("schedule run completed"));
        assertThat(end)
                .containsEntry("loresentry.schedule.name", "sample-purge")
                .containsEntry("loresentry.result.deleted", 3)
                .containsEntry("log.layer", "scheduler");
        assertThat(end.get("event.duration")).isInstanceOf(Long.class);
        // 흐름 안에서 찍은 줄에도 작업 이름이 붙고, 프레임워크 줄에도 붙도록 MDC에도 있다
        assertThat(pairs(line("sample rows delete")))
                .containsEntry("loresentry.schedule.name", "sample-purge");
        assertThat(line("sample rows delete").getMDCPropertyMap())
                .containsEntry("loresentry.schedule.name", "sample-purge");
        // 흐름이 끝나면 MDC에 남지 않는다
        assertThat(MDC.get("loresentry.schedule.name")).isNull();
    }

    @Test
    void aFailingScheduleSwallowsTheExceptionAndWritesAFailureLineWithoutTheMessage() {
        new SamplePurge().fail();
        assertThat(messages()).containsExactly("schedule run start", "schedule run failed");
        ILoggingEvent failed = line("schedule run failed");
        assertThat(failed.getLevel().toString()).isEqualTo("ERROR");
        Map<String, Object> fields = pairs(failed);
        assertThat(fields)
                .containsEntry("error.type", "java.lang.IllegalStateException")
                .containsKey("error.stack_trace")
                .containsKey("event.duration");
        // 예외 메시지(SQL·요청 값이 섞일 수 있다)는 어디에도 남지 않는다
        assertThat(String.valueOf(fields.get("error.stack_trace"))).doesNotContain("secret sql");
        assertThat(failed.getThrowableProxy()).isNull();
    }

    @Test
    void aPollingRoundWithoutLinesLeavesNothingAndARoundWithWorkLeavesStartAndEnd() {
        LogFlow.polling("sample-relay", run -> run.result(LogField.result("published", 0)));
        assertThat(events.list).isEmpty();

        LogFlow.polling(
                "sample-relay",
                run -> {
                    AppLogger.current().info("sample batch start");
                    run.result(LogField.result("published", 2));
                });
        assertThat(messages())
                .containsExactly(
                        "schedule run start", "sample batch start", "schedule run completed");
        assertThat(pairs(line("schedule run completed")))
                .containsEntry("loresentry.result.published", 2)
                .containsEntry("loresentry.schedule.name", "sample-relay");
    }

    @Test
    void onlyAnIdlePollingRoundMarksItsSpanForTheCollectorToDrop() {
        // Spring이 @Scheduled 실행마다 여는 span 흉내. 빈 회차의 span에만 표시가 붙고, 일이 있던 회차와 실패한 회차는 그대로 남는다
        Tracer tracer = SdkTracerProvider.builder().build().get("test");

        ReadableSpan idle =
                inSpan(
                        tracer,
                        () ->
                                LogFlow.polling(
                                        "sample-relay",
                                        run -> run.result(LogField.result("published", 0))));
        ReadableSpan busy =
                inSpan(
                        tracer,
                        () ->
                                LogFlow.polling(
                                        "sample-relay",
                                        run -> AppLogger.current().info("sample batch start")));
        ReadableSpan failed =
                inSpan(
                        tracer,
                        () -> LogFlow.polling("sample-relay", run -> run.fail("SAMPLE_FAILED")));

        assertThat(idle.getAttribute(TraceContext.IDLE_POLLING)).isTrue();
        assertThat(busy.getAttribute(TraceContext.IDLE_POLLING)).isNull();
        assertThat(failed.getAttribute(TraceContext.IDLE_POLLING)).isNull();
    }

    /** span을 지금 span으로 두고 할 일을 한 뒤 닫는다. 닫힌 span의 속성을 읽으려고 SDK의 ReadableSpan으로 돌려준다. */
    private static ReadableSpan inSpan(Tracer tracer, Runnable work) {
        Span span = tracer.spanBuilder("scheduled run").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            work.run();
        } finally {
            span.end();
        }
        return (ReadableSpan) span;
    }

    @Test
    void aPollingFailureWritesTheStartLineFirstAndAFailedRoundIsAWarning() {
        LogFlow.polling(
                "sample-relay",
                run -> {
                    throw new IllegalStateException("boom");
                });
        assertThat(messages()).containsExactly("schedule run start", "schedule run failed");

        events.list.clear();
        LogFlow.polling(
                "sample-relay",
                run -> {
                    AppLogger.current().info("sample batch start");
                    run.fail("OUTBOX_SEND_FAILED");
                });
        ILoggingEvent failed = line("schedule run failed");
        assertThat(failed.getLevel().toString()).isEqualTo("WARN");
        assertThat(pairs(failed)).containsEntry("error.type", "OUTBOX_SEND_FAILED");
    }

    @Test
    void aCallWritesOneResultLineAndAServerErrorIsAWarning() {
        String value =
                LogFlow.call(
                        "redis",
                        call -> {
                            call.status(200);
                            return "ok";
                        });
        assertThat(value).isEqualTo("ok");
        assertThat(messages()).containsExactly("redis call completed");
        assertThat(pairs(line("redis call completed")))
                .containsEntry("http.response.status_code", 200L)
                .containsKey("event.duration");

        events.list.clear();
        LogFlow.call("google", call -> call.status(503));
        ILoggingEvent completed = line("google call completed");
        assertThat(completed.getLevel().toString()).isEqualTo("WARN");
        assertThat(pairs(completed)).containsEntry("error.type", "UPSTREAM_SERVER_ERROR");

        // 검사 예외도 그대로 다시 던지고 결과 줄을 남긴다
        events.list.clear();
        assertThatThrownBy(
                        () ->
                                LogFlow.call(
                                        "redis",
                                        call -> {
                                            throw new IOException("network secret");
                                        }))
                .isInstanceOf(IOException.class);
        ILoggingEvent failed = line("redis call failed");
        assertThat(failed.getLevel().toString()).isEqualTo("WARN");
        assertThat(pairs(failed))
                .containsEntry("error.type", "java.io.IOException")
                .containsKey("error.stack_trace")
                .containsKey("event.duration");
        assertThat(String.valueOf(pairs(failed).get("error.stack_trace")))
                .doesNotContain("network secret");
    }

    @Test
    void aCallWithAnAddressNamesTheHostAndPathButNotTheQuery() {
        LogFlow.call(
                "google",
                URI.create("https://oauth2.googleapis.com:443/token?code=secret-code"),
                call -> call.status(200));
        Map<String, Object> fields = pairs(line("google call completed"));
        assertThat(fields)
                .containsEntry("url.domain", "oauth2.googleapis.com")
                .containsEntry("url.full", "https://oauth2.googleapis.com:443/token")
                .containsEntry("http.response.status_code", 200L)
                .containsKey("event.duration");
        assertThat(fields.values()).noneMatch(v -> String.valueOf(v).contains("secret-code"));
    }

    @Test
    void aTaskWritesStartAndEndLinesInTheCurrentFlow() {
        LogFlow.task(
                "sample cleanup",
                task -> task.result(LogField.result("deleted", 1)),
                LogField.param("keys", 2));
        assertThat(messages()).containsExactly("sample cleanup start", "sample cleanup completed");
        assertThat(pairs(line("sample cleanup start"))).containsEntry("loresentry.param.keys", 2);
        assertThat(pairs(line("sample cleanup completed")))
                .containsEntry("loresentry.result.deleted", 1)
                .containsKey("event.duration");

        events.list.clear();
        LogFlow.task("sample cleanup", task -> task.fail("SAMPLE_CLEANUP_FAILED"));
        ILoggingEvent failed = line("sample cleanup failed");
        assertThat(failed.getLevel().toString()).isEqualTo("WARN");
        assertThat(pairs(failed)).containsEntry("error.type", "SAMPLE_CLEANUP_FAILED");
    }

    @Test
    void aRequestWritesTheAccessLinesWithTheHttpContextOnEveryLine() throws Exception {
        UUID user = UUID.randomUUID();
        new SampleFilter().handle("/auth/sample/1", 200, user, null);
        assertThat(messages())
                .containsExactly("request received", "sample handle", "request completed");
        // 요청 하나의 모든 줄에 받은 경로·경로 템플릿·메서드와 gateway가 보낸 사용자가 붙는다
        assertThat(events.list)
                .allSatisfy(
                        event -> {
                            assertThat(pairs(event))
                                    .containsEntry("url.path", "/auth/sample/1")
                                    .containsEntry("http.route", "/auth/sample/{id}")
                                    .containsEntry("http.request.method", "POST");
                            assertThat(event.getMDCPropertyMap())
                                    .containsEntry("http.request.method", "POST")
                                    .containsEntry("user.id", user.toString());
                        });
        ILoggingEvent completed = line("request completed");
        assertThat(completed.getLevel().toString()).isEqualTo("INFO");
        assertThat(pairs(completed))
                .containsEntry("http.response.status_code", 200L)
                .containsEntry("log.layer", "filter")
                .containsEntry("log.origin.function", "handle")
                .containsKey("event.duration")
                .doesNotContainKey("error.type");
        assertThat(pairs(line("request received"))).containsEntry("log.layer", "filter");
        // 요청이 끝나면 문맥이 MDC에 남지 않는다
        assertThat(MDC.get("url.path")).isNull();
        assertThat(MDC.get("user.id")).isNull();
    }

    @Test
    void theStatusPicksTheLevelAndTheOutcomeAndIdentifiedUserAreOnTheResultLine() throws Exception {
        UUID user = UUID.randomUUID();
        // 콜백처럼 X-User-Id가 없는 요청에서 흐름 중간에 알게 된 사용자가 결과 줄에 붙는다
        new SampleFilter().handle("/auth/sample/2", 401, null, user);
        ILoggingEvent warned = line("request completed");
        assertThat(warned.getLevel().toString()).isEqualTo("WARN");
        assertThat(pairs(warned))
                .containsEntry("http.response.status_code", 401L)
                .containsEntry("error.type", "SAMPLE_FAILED");
        assertThat(warned.getMDCPropertyMap()).containsEntry("user.id", user.toString());
        assertThat(line("request received").getMDCPropertyMap()).doesNotContainKey("user.id");

        events.list.clear();
        new SampleFilter().handle("/auth/sample/3", 503, null, null);
        assertThat(line("request completed").getLevel().toString()).isEqualTo("ERROR");
    }

    @Test
    void anUnhandledFailureStillEndsTheRequestWithoutTheMessage() {
        assertThatThrownBy(
                        () ->
                                LogFlow.request(
                                        "/auth/sample/4",
                                        null,
                                        "GET",
                                        null,
                                        flow -> {
                                            AppLogger.current()
                                                    .addToOutcome(
                                                            LogField.errorType("INTERNAL_ERROR"));
                                            throw new ServletException("secret-token");
                                        }))
                .isInstanceOf(ServletException.class);
        ILoggingEvent failed = line("request failed with an unhandled exception");
        assertThat(failed.getLevel().toString()).isEqualTo("ERROR");
        // 처리되지 않은 예외의 종류가 맡겨진 결과 필드보다 앞선다
        assertThat(pairs(failed))
                .containsEntry("error.type", "jakarta.servlet.ServletException")
                .containsEntry("http.response.status_code", 500L)
                .containsKey("error.stack_trace")
                .containsKey("event.duration");
        assertThat(String.valueOf(pairs(failed).get("error.stack_trace")))
                .doesNotContain("secret-token");
        assertThat(failed.getThrowableProxy()).isNull();
        assertThat(messages()).doesNotContain("request completed");

        // java.lang.Error로 끝난 요청에도 종료 줄이 남는다
        events.list.clear();
        assertThatThrownBy(
                        () ->
                                LogFlow.request(
                                        "/auth/sample/5",
                                        null,
                                        "GET",
                                        null,
                                        flow -> {
                                            throw new StackOverflowError();
                                        }))
                .isInstanceOf(StackOverflowError.class);
        assertThat(pairs(line("request failed with an unhandled exception")))
                .containsEntry("error.type", "java.lang.StackOverflowError");
    }

    @Test
    void healthCheckRequestsAreTaggedHealthOnEveryLine() throws Exception {
        LogFlow.request(
                "/health/db",
                "/health/db",
                "GET",
                null,
                flow -> {
                    AppLogger.current().info("sample health");
                    flow.status(200);
                });
        assertThat(events.list)
                .hasSize(3)
                .allSatisfy(
                        event ->
                                assertThat(event.getMarkerList())
                                        .extracting(org.slf4j.Marker::getName)
                                        .containsExactly("health"));
    }

    @Test
    void anOutboxRowScopeCarriesTheRowTraceEventFieldsAndItsOwnUser() {
        UUID id = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        try (var row =
                LogScope.outboxEvent(
                        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                        "outbox-relay",
                        id,
                        "UserDeleted",
                        user)) {
            AppLogger.current().identify(user);
            AppLogger.current().info("outbox event published");
        }
        ILoggingEvent published = line("outbox event published");
        assertThat(published.getMDCPropertyMap())
                .containsEntry("trace.id", "0af7651916cd43dd8448eb211c80319c")
                .containsEntry("user.id", user.toString())
                .containsEntry("loresentry.cloudevent.subject", user.toString());
        assertThat(pairs(published))
                .containsEntry("loresentry.cloudevent.id", id.toString())
                .containsEntry("loresentry.cloudevent.type", "UserDeleted")
                .containsEntry("loresentry.cloudevent.subject", user.toString())
                .containsEntry("loresentry.schedule.name", "outbox-relay");
        // 범위가 닫히면 그 행의 사용자·문맥은 남지 않는다
        assertThat(MDC.get("user.id")).isNull();
        assertThat(MDC.get("loresentry.cloudevent.id")).isNull();
    }

    @Test
    void aStoredTraceparentBecomesTheTraceOfTheLine() {
        // outbox 행에 저장해 둔 traceparent로 원래 흐름의 trace를 잇는다(LOG_SCHEMA.md 7절)
        AppLogger stored =
                TraceContext.fromTraceparent(
                        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertThat(stored.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(TraceContext.fromTraceparent("not-a-traceparent").traceId()).isNull();
        assertThat(TraceContext.fromTraceparent(null).traceId()).isNull();
    }
}
