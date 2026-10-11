package com.loresentry.authentication.adapter.out.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 보낼 {@code outbox_events} 행 하나. relay 가 메시지를 조립하는 데 필요한 칸만 담는다 — 발행 여부나 실패 기록은 relay 가 행을 고르고 관리하는
 * 데 쓰는 값이지 메시지의 내용이 아니다.
 *
 * <p>{@code eventKey} 와 {@code aggregateId} 는 뜻이 다르다. key 는 Kafka 가 순서를 지키는 단위이고, aggregate 는 이
 * 이벤트가 알리는 대상이다. UserDeleted 에서는 둘 다 사용자 id 라 값이 같다.
 *
 * <p>{@code seq} 는 보낼 순서이면서 메시지에도 실린다. 소비자가 같은 대상의 이벤트 가운데 어느 쪽이 더 최신인지 이 값으로 가린다.
 *
 * <p>{@code payload} 는 JSON 문자열 그대로다. relay 는 내용을 볼 일이 없어서 객체로 풀지 않고 그대로 싣는다.
 */
record OutboxEvent(
        UUID id,
        long seq,
        String eventType,
        String eventKey,
        UUID aggregateId,
        String payload,
        OffsetDateTime createdAt,
        String traceparent) {}
