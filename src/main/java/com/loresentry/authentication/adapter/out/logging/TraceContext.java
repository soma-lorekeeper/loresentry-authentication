package com.loresentry.authentication.adapter.out.logging;

import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.UUID;

/**
 * 지금 열려 있는 span을 로그 시작점과 outbox가 쓰는 모양으로 꺼내고, 로깅 팩토리가 여는 우리 span의 tracer를 준다(LOG_SCHEMA.md 7절).
 * content의 같은 이름 클래스와 같다.
 *
 * <p>span은 EDOT Java agent가 연다: HTTP 요청 하나(Tomcat 계측, gateway가 보낸 traceparent를 잇는다), 스케줄 작업
 * 1회(Spring Scheduling 계측), JDBC·Redis·Google·Kafka 발행 호출. 앱은 OpenTelemetry API만 갖고, agent가 그 API를
 * 자기 SDK에 잇는다. 그래서 {@link Span#current()}가 agent가 연 지금 span이고, {@link #tracer()}로 연 우리 span도 agent가
 * 내보낸다. agent가 없는 테스트에서는 API가 아무것도 하지 않거나(지금 span 없음), 테스트가 꽂은 OpenTelemetry SDK가 대신한다.
 *
 * <p>서비스 코드가 부르는 것은 {@link #traceparent()}(outbox 행에 저장할 전파 값, 로그가 아니다)뿐이다. 시작점의 로그 객체를 만드는 메서드와
 * tracer는 로깅 팩토리({@link LogFlow}·{@link LogScope})만 부르므로 이 패키지 안에서만 보인다.
 *
 * <p>OpenTelemetry를 쓰므로 core가 쓰는 logging 패키지가 아니라 adapter에 둔다.
 */
public final class TraceContext {
    /**
     * 로깅 팩토리가 여는 span의 계측 이름(scope.name). gitops {@code logging/tools/apm_consistency.py}가 이 이름으로
     * 우리 span을 알아본다(흐름 밖에서 연 작업 span은 허용된 root다).
     */
    static final String INSTRUMENTATION_SCOPE = "com.loresentry.authentication.logging";

    /** 헬스체크 요청의 tags 값. 그 요청의 모든 줄에 붙고, Logstash가 정상(info) 줄을 버린다. */
    private static final String HEALTH_TAG = "health";

    /**
     * 할 일이 없던 폴링 회차의 span 표시. EDOT Collector가 이 속성이 true인 span의 trace를 APM에 쓰기 전에 버린다(gitops {@code
     * logging/base/otel-collector/otel.yml}의 tail_sampling, LOG_SCHEMA.md 7절).
     */
    static final AttributeKey<Boolean> IDLE_POLLING =
            AttributeKey.booleanKey("loresentry.polling.idle");

    /** 저장해 둔 traceparent 값 하나를 W3C 전파기에 헤더 하나처럼 넘기는 어댑터. */
    private static final TextMapGetter<String> STORED_TRACEPARENT =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(String carrier) {
                    return List.of("traceparent");
                }

                @Override
                public String get(String carrier, String key) {
                    return "traceparent".equals(key) ? carrier : null;
                }
            };

    private TraceContext() {}

    /**
     * 로깅 팩토리가 우리 span(외부 호출 감싸기, 작업, outbox 행)을 열 때 쓰는 tracer. 실행 중에는 agent의 tracer다. 테스트가 SDK를 바꿔
     * 꽂을 수 있도록 부를 때마다 꺼낸다(GlobalOpenTelemetry가 tracer를 캐시한다).
     */
    static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /**
     * 저장해 둔 traceparent를 다른 쪽(원래 요청)의 span 문맥으로 읽는다. W3C 전파기가 형식(version 00, 32·16 hex, 0이 아닌 ID)을
     * 확인한다. 값이 없거나 틀리면 유효하지 않은 문맥이다.
     */
    static SpanContext stored(String traceparent) {
        if (traceparent == null) return SpanContext.getInvalid();
        Context extracted =
                W3CTraceContextPropagator.getInstance()
                        .extract(Context.root(), traceparent, STORED_TRACEPARENT);
        return Span.fromContext(extracted).getSpanContext();
    }

    /**
     * 지금 span에 빈 폴링 회차 표시를 단다. 폴링 회차의 span은 우리 코드가 아니라 agent가 {@code @Scheduled} 실행마다 연다. 그래서 빈
     * 회차에도 span이 생기는데, 끄면 할 일이 있던 회차의 로그도 trace를 잃는다. 대신 빈 회차임을 표시해 collector가 버리게 한다. 빈 회차는 줄도 자식
     * span도 없어 버려도 끊기는 것이 없다. span이 없으면 아무것도 하지 않는다.
     */
    static void markIdlePolling() {
        Span.current().setAttribute(IDLE_POLLING, true);
    }

    /**
     * 지금 span의 ID로 시작점의 로그 객체를 만든다. span이 없으면 trace 없는 객체다. 줄마다 남는 span.id는 이 값이 아니라 줄을 찍는 순간의 지금
     * span이다(EcsLogFormatter). 이 값은 지금 span이 없거나 다른 trace일 때 쓰는 흐름의 값이다.
     */
    static AppLogger newLogger() {
        return loggerOf(Span.current().getSpanContext());
    }

    private static AppLogger loggerOf(SpanContext span) {
        return span.isValid()
                ? new AppLogger(span.getTraceId(), span.getSpanId(), null)
                : new AppLogger(null, null, null);
    }

    /**
     * 지금 span의 W3C traceparent 값({@code 00-<trace>-<span>-<flags>}, 55자). outbox 행에 저장하면 relay가 그
     * 값으로 행의 span을 열고 그 안에서 발행해, 받는 서비스(content)가 이 요청의 trace를 잇는다. span이 없으면 null이다.
     */
    public static String traceparent() {
        SpanContext span = Span.current().getSpanContext();
        if (!span.isValid()) return null;
        return "00-"
                + span.getTraceId()
                + "-"
                + span.getSpanId()
                + "-"
                + span.getTraceFlags().asHex();
    }

    /**
     * gateway가 부른 요청 하나의 시작점 로그 객체. agent가 연 서버 span(gateway가 보낸 traceparent를 이은 것)과 gateway가 확인해
     * 보낸 사용자로 만들고, 요청 하나의 모든 줄에 받은 경로({@code url.path}), 경로 템플릿({@code http.route}), 메서드({@code
     * http.request.method})를 붙인다. 이 값들은 MDC에도 들어가 같은 스레드의 프레임워크 줄(Spring, Hibernate 등)에도 붙는다. 헬스체크
     * 요청이면 모든 줄에 {@code tags: health}를 붙인다.
     *
     * @param path 받은 경로(쿼리 제외)
     * @param route 경로 템플릿. 맞는 경로가 없으면(404) null
     * @param method 요청 메서드
     * @param user gateway가 보낸 사용자. 없으면 null
     */
    static AppLogger forRequest(String path, String route, String method, UUID user) {
        AppLogger logger =
                newLogger()
                        .withUser(user)
                        .with(
                                LogField.urlPath(path),
                                LogField.route(route),
                                LogField.httpMethod(method));
        // 정상(info) 줄은 Logstash가 버리고, 실패한 헬스체크의 warn·error는 남는다
        return health(path) ? logger.withTag(HEALTH_TAG) : logger;
    }

    /**
     * 스케줄 작업 1회의 시작점 로그 객체. agent가 회차마다 연 span으로 만들고 작업 이름(schedule.name)을 붙여, 이 회차의 모든 줄(부른
     * repository 줄 포함)에 남게 한다.
     */
    static AppLogger forSchedule(String name) {
        return newLogger().with(LogField.scheduleName(name));
    }

    /**
     * outbox 행 하나의 로그 객체. 행의 span({@link LogScope#outboxEvent}가 행에 저장된 traceparent를 부모로 연 span)으로
     * 만들어, 요청의 흐름 끝에 이 이벤트가 어떻게 됐는지(발행, 실패 기록, 격리) 보이게 한다. relay 작업 이름과 이벤트의 ID·종류·대상
     * (ce_id·ce_type·ce_subject), 서비스가 넘긴 도메인 ID를 문맥 필드로 붙인다. 받는 서비스의 inbox 줄과 같은 cloudevent 필드다.
     *
     * @param row 행의 span 문맥
     */
    static AppLogger forOutboxEvent(
            SpanContext row, String schedule, UUID id, String type, UUID subject, LogField... ids) {
        return loggerOf(row)
                .with(
                        LogField.scheduleName(schedule),
                        LogField.cloudeventId(id),
                        LogField.cloudeventType(type),
                        LogField.cloudeventSubject(subject))
                .with(ids);
    }

    private static boolean health(String path) {
        return "/health".equals(path) || path.startsWith("/health/");
    }
}
