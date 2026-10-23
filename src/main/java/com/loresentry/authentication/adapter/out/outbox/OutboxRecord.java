package com.loresentry.authentication.adapter.out.outbox;

import java.util.UUID;

/**
 * outbox 에 쓸 이벤트 하나. {@link OutboxRepository} 는 이 값들이 무슨 뜻인지 모른다 — 어느 topic 으로 갈지는 {@link
 * OutboxMessages#topicOf} 가 {@code eventType} 으로 정하고, payload 의 모양은 메시지를 만든 쪽(adapter.out.event 의
 * factory)만 안다.
 *
 * @param eventType header {@code ce_type}. topic 도 이것으로 정해진다
 * @param aggregateId header {@code ce_subject}. 이벤트가 알리는 대상이다
 * @param key Kafka key. 같은 key 의 이벤트는 같은 partition 으로 가서 순서가 지켜진다
 * @param payload value 로 보낼 객체. {@link OutboxRepository#append} 가 JSON 으로 바꿔 쓴다
 */
public record OutboxRecord(String eventType, UUID aggregateId, String key, Object payload) {}
