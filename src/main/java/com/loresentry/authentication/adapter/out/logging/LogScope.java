package com.loresentry.authentication.adapter.out.logging;

import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.UUID;

/**
 * 저장했다 나중에 처리하는 일 하나의 범위(LOG_SCHEMA.md 5절 "만들기", 7절 "저장했다 나중에 처리"). 시작·종료 줄은 남기지 않는다. content의 같은
 * 이름 클래스와 같다(이 서비스는 Kafka를 받지 않아 outbox 행 범위만 있다).
 *
 * <ul>
 *   <li>{@link #outboxEvent}: outbox 행 하나. 그 행을 만든 요청의 trace(행에 저장된 traceparent)를 잇는다
 * </ul>
 *
 * <p>범위를 열면 저장된 traceparent를 부모로 한 span({@code outbox <event type>}, INTERNAL)이 시작된다. 서비스 코드는 할 일을
 * 람다로 넘긴다({@link #call}·{@link #run}). 그동안 이 span이 지금 문맥이 되고 행의 로그 객체가 바인딩된다. 그래서
 *
 * <ul>
 *   <li>그 안의 모든 줄(부른 repository 줄 포함)이 행의 trace와 이 span의 span.id, 문맥 필드로 남는다
 *   <li>그 안의 계측 span(Kafka 발행, JDBC)이 이 span의 자식이 된다. Kafka 발행 계측은 보내는 순간의 지금 문맥으로 {@code
 *       traceparent} 헤더를 넣으므로(같은 이름의 헤더는 지우고 다시 넣는다) 받는 서비스가 원래 요청의 trace를 잇는다. 이 범위 밖에서 보내면 relay
 *       회차의 trace가 헤더에 실린다
 * </ul>
 *
 * <p>한 행의 일이 여러 번에 나뉘어도(보내기, 결과 기다린 뒤 줄 남기기, 실패 기록) 같은 범위로 {@link #call}·{@link #run}을 여러 번 부르고, 행의
 * 일이 끝나면 {@link #close}로 span을 닫는다. 행마다 span은 하나다. 서비스 코드는 span을 다루지 않는다(LOG_SCHEMA.md 5절,
 * ArchUnit).
 *
 * <p>시작·종료가 있는 흐름은 {@link LogFlow}를 쓴다.
 */
public final class LogScope implements AutoCloseable {
    /** 범위 안에서 할 일. 값을 돌려준다(예: Kafka 전송 결과). */
    @FunctionalInterface
    public interface Work<T, E extends Exception> {
        T run() throws E;
    }

    /** 범위 안에서 할 일. 돌려주는 값이 없다. */
    @FunctionalInterface
    public interface Task<E extends Exception> {
        void run() throws E;
    }

    /** 행의 로그 객체. 범위 안에서 알린 사용자({@code identify})는 이 객체의 공유 칸에 남아 그 행의 뒤 줄에도 붙는다. */
    private final AppLogger logger;

    /** 행의 span. {@link #close}가 닫는다. */
    private final Span span;

    private LogScope(AppLogger logger, Span span) {
        this.logger = logger;
        this.span = span;
    }

    /**
     * outbox 행 하나의 범위를 연다. 행에 저장된 traceparent를 부모로 span을 시작하고, 그 span과 relay 작업 이름, 이벤트의 ID·종류·대상
     * (ce_id·ce_type·ce_subject), 서비스가 넘긴 도메인 ID로 행의 로그 객체를 만든다. 받는 서비스의 inbox 줄과 같은 cloudevent
     * 필드다.
     *
     * <p>traceparent가 없거나 형식이 틀린 행(추적을 붙이기 전에 쌓인 행)은 그 행만 새 trace로 시작한다. relay 회차와 행은 다른 trace라, 회차
     * span을 link로 남겨 APM에서 회차에서 행으로 건너갈 수 있게 한다.
     *
     * @param traceparent 행의 traceparent
     * @param schedule relay 작업 이름
     * @param ids 이벤트가 가리키는 도메인 ID
     */
    public static LogScope outboxEvent(
            String traceparent,
            String schedule,
            UUID id,
            String type,
            UUID subject,
            LogField... ids) {
        SpanContext stored = TraceContext.stored(traceparent);
        SpanContext round = Span.current().getSpanContext();
        SpanBuilder builder =
                TraceContext.tracer().spanBuilder("outbox " + type).setSpanKind(SpanKind.INTERNAL);
        if (stored.isValid()) builder.setParent(Context.root().with(Span.wrap(stored)));
        else builder.setNoParent();
        if (round.isValid() && !round.getTraceId().equals(stored.getTraceId()))
            builder.addLink(round);
        Span span = builder.startSpan();
        return new LogScope(
                TraceContext.forOutboxEvent(
                        span.getSpanContext(), schedule, id, type, subject, ids),
                span);
    }

    /**
     * 범위 안에서 할 일을 하고 그 값을 돌려준다. 그동안 행의 span이 지금 문맥이고 행의 로그 객체가 바인딩된다. 예외는 span을 실패로 표시한 뒤 그대로 다시
     * 던진다(span에는 예외 종류만 남는다. 메시지와 스택은 collector가 지운다).
     */
    public <T, E extends Exception> T call(Work<T, E> work) throws E {
        try (Scope ignored = span.makeCurrent();
                AppLogger.Binding bound = AppLogger.bind(logger)) {
            return work.run();
        } catch (Throwable failure) {
            markFailed(failure);
            throw failure;
        }
    }

    /** 돌려주는 값이 없는 {@link #call}. */
    public <E extends Exception> void run(Task<E> task) throws E {
        call(
                () -> {
                    task.run();
                    return null;
                });
    }

    /**
     * 예외 없이 끝난 실패를 알린다(예: 격리한 행, 보내지 못한 행). 행의 span이 실패로 표시된다. 이유는 로그에 남긴 error.type과 같은 값을 넘긴다.
     */
    public void fail(String errorType) {
        span.setStatus(StatusCode.ERROR, errorType);
    }

    /** 행의 일이 끝났다. 행의 span을 닫는다. */
    @Override
    public void close() {
        span.end();
    }

    private void markFailed(Throwable failure) {
        span.setStatus(StatusCode.ERROR, failure.getClass().getName());
        span.recordException(failure);
    }
}
