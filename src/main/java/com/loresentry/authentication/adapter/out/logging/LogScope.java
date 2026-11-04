package com.loresentry.authentication.adapter.out.logging;

import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.util.UUID;

/**
 * 시작·종료 줄 없이 로그 객체만 바인딩하는 범위(LOG_SCHEMA.md 5절 "만들기"). 서비스 코드는 재료만 넘기고 try-with-resources로 연다. 범위 안의
 * 모든 줄(부른 repository 줄 포함)에 그 대상의 trace와 문맥 필드가 붙고, 닫으면 이전 바인딩으로 돌아간다. content의 같은 이름 클래스와 같다(이
 * 서비스는 Kafka를 받지 않아 outbox 행 범위만 있다).
 *
 * <ul>
 *   <li>{@link #outboxEvent}: outbox 행 하나의 줄(발행, 실패 기록, 격리). 그 행을 만든 요청의 trace로 남는다
 * </ul>
 *
 * <p>시작·종료가 있는 흐름은 {@link LogFlow}를 쓴다.
 */
public final class LogScope implements AutoCloseable {
    /** 이 범위가 연 바인딩. 닫으면 이전 객체와 MDC 값으로 되돌린다. */
    private final AppLogger.Binding binding;

    private LogScope(AppLogger logger) {
        this.binding = AppLogger.bind(logger);
    }

    /**
     * outbox 행 하나의 범위. 행에 저장된 traceparent(그 행을 만든 요청의 trace), relay 작업 이름, 이벤트의 ID·종류·대상
     * (ce_id·ce_type·ce_subject)과 서비스가 넘긴 도메인 ID를 붙인다. 받는 서비스의 inbox 줄과 같은 cloudevent 필드다. 범위는 그 행의
     * 공유 칸을 따로 가지므로, 범위 안에서 알린 사용자({@code identify})는 그 행의 줄에만 붙는다.
     *
     * @param traceparent 행의 traceparent. 없으면(추적 전에 쌓인 행) relay 회차의 trace가 붙는다
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
        return new LogScope(
                TraceContext.forOutboxEvent(traceparent, schedule, id, type, subject, ids));
    }

    @Override
    public void close() {
        binding.close();
    }
}
