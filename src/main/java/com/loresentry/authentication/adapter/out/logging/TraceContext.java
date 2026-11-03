package com.loresentry.authentication.adapter.out.logging;

import com.loresentry.authentication.logging.AppLogger;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 지금 열려 있는 span을 로그 시작점과 outbox가 쓰는 모양으로 꺼낸다(LOG_SCHEMA.md 7절). content의 같은 이름 클래스와 같다.
 *
 * <p>span은 Spring observation이 연다: HTTP 요청 하나(서버 observation 필터), 스케줄 작업 1회(스케줄 observation).
 * Micrometer Tracing의 OpenTelemetry bridge는 span을 OpenTelemetry context에 두므로 OpenTelemetry API의
 * {@link Span#current()}로 읽는다. 그래서 이 값이 필요한 곳(요청 필터, outbox 저장소·발행)이 Tracer를 생성자로 받지 않아도 된다.
 *
 * <p>OpenTelemetry를 쓰므로 core가 쓰는 logging 패키지가 아니라 adapter에 둔다.
 */
public final class TraceContext {
    /** W3C traceparent version 00: 00-<trace-id 32 hex>-<parent span-id 16 hex>-<flags 2 hex>. */
    private static final Pattern TRACEPARENT =
            Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    private TraceContext() {}

    /** 지금 span의 ID로 시작점의 로그 객체를 만든다. span이 없으면 trace 없는 객체다. */
    public static AppLogger newLogger() {
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
    public static AppLogger fromTraceparent(String traceparent) {
        Matcher matcher = traceparent == null ? null : TRACEPARENT.matcher(traceparent);
        if (matcher == null || !matcher.matches()) return new AppLogger(null, null, null);
        return new AppLogger(matcher.group(1), matcher.group(2), null);
    }
}
