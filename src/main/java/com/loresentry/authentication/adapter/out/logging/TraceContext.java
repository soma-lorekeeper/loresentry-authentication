package com.loresentry.authentication.adapter.out.logging;

import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 지금 열려 있는 span을 로그 시작점과 outbox가 쓰는 모양으로 꺼낸다(LOG_SCHEMA.md 7절). content의 같은 이름 클래스와 같다.
 *
 * <p>span은 Spring observation이 연다: HTTP 요청 하나(서버 observation 필터), 스케줄 작업 1회(스케줄 observation).
 * Micrometer Tracing의 OpenTelemetry bridge는 span을 OpenTelemetry context에 두므로 OpenTelemetry API의
 * {@link Span#current()}로 읽는다. 그래서 이 값이 필요한 곳(요청 필터, outbox 저장소·발행)이 Tracer를 생성자로 받지 않아도 된다.
 *
 * <p>서비스 코드가 부르는 것은 {@link #traceparent()}(outbox 행에 저장할 전파 값, 로그가 아니다)뿐이다. 시작점의 로그 객체를 만드는 메서드는 로깅
 * 팩토리({@link LogFlow}·{@link LogScope})만 부르므로 이 패키지 안에서만 보인다.
 *
 * <p>OpenTelemetry를 쓰므로 core가 쓰는 logging 패키지가 아니라 adapter에 둔다.
 */
public final class TraceContext {
    /** W3C traceparent version 00: 00-<trace-id 32 hex>-<parent span-id 16 hex>-<flags 2 hex>. */
    private static final Pattern TRACEPARENT =
            Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    /** 헬스체크 요청의 tags 값. 그 요청의 모든 줄에 붙고, Logstash가 정상(info) 줄을 버린다. */
    private static final String HEALTH_TAG = "health";

    private TraceContext() {}

    /** 지금 span의 ID로 시작점의 로그 객체를 만든다. span이 없으면 trace 없는 객체다. */
    static AppLogger newLogger() {
        SpanContext span = Span.current().getSpanContext();
        return span.isValid()
                ? new AppLogger(span.getTraceId(), span.getSpanId(), null)
                : new AppLogger(null, null, null);
    }

    /**
     * 지금 span의 W3C traceparent 값({@code 00-<trace>-<span>-<flags>}, 55자). outbox 행에 저장하면 relay가 그
     * 값을 Kafka 헤더로 실어, 받는 서비스(content)가 이 요청의 trace를 잇는다. span이 없으면 null이다.
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
     * 저장해 둔 traceparent의 trace로 로그 객체를 만든다. 저장했다 나중에 처리하는 일(outbox 행 발행)이 원래 요청의 trace로 줄을 남길 때 쓴다.
     * span.id는 그 값을 저장한 쪽의 span이다. 값이 없거나 형식이 틀리면 trace 없는 객체다.
     */
    static AppLogger fromTraceparent(String traceparent) {
        Matcher matcher = traceparent == null ? null : TRACEPARENT.matcher(traceparent);
        if (matcher == null || !matcher.matches()) return new AppLogger(null, null, null);
        return new AppLogger(matcher.group(1), matcher.group(2), null);
    }

    /**
     * gateway가 부른 요청 하나의 시작점 로그 객체. 서버 observation이 연 span(gateway가 보낸 traceparent를 이은 것)과 gateway가
     * 확인해 보낸 사용자로 만들고, 요청 하나의 모든 줄에 받은 경로({@code url.path}), 경로 템플릿({@code http.route}), 메서드({@code
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
     * 스케줄 작업 1회의 시작점 로그 객체. 스케줄 observation이 회차마다 연 span으로 만들고 작업 이름(schedule.name)을 붙여, 이 회차의 모든
     * 줄(부른 repository 줄 포함)에 남게 한다.
     */
    static AppLogger forSchedule(String name) {
        return newLogger().with(LogField.scheduleName(name));
    }

    /**
     * outbox 행 하나의 로그 객체. 그 행을 만든 요청의 trace(행에 저장된 traceparent)로 만들어, 요청의 흐름 끝에 이 이벤트가 어떻게 됐는지(발행,
     * 실패 기록, 격리) 보이게 한다. traceparent가 없는 행(추적 전에 쌓인 행)은 relay 회차의 trace가 붙는다(EcsLogFormatter가
     * Micrometer Tracing이 넣은 지금 span으로 채운다). relay 작업 이름과 이벤트의 ID·종류·대상(ce_id·ce_type·ce_subject),
     * 서비스가 넘긴 도메인 ID를 문맥 필드로 붙인다. 받는 서비스의 inbox 줄과 같은 cloudevent 필드다.
     */
    static AppLogger forOutboxEvent(
            String traceparent,
            String schedule,
            UUID id,
            String type,
            UUID subject,
            LogField... ids) {
        return fromTraceparent(traceparent)
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
