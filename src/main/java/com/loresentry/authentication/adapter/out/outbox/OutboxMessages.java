package com.loresentry.authentication.adapter.out.outbox;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * outbox 행을 Kafka 메시지로 옮긴다. 팀 Kafka 설계 문서의 "Message 계약"이 정한 topic, key, header 가 이 클래스 한 곳에 모여 있다.
 *
 * <p>CloudEvents 1.0 Kafka binding 의 binary mode 다. value 에는 이벤트 데이터만 넣고 이벤트에 대한 정보는 header 로 보낸다.
 * 소비자는 value 를 파싱하기 전에 header 만 보고 세 가지를 가릴 수 있다: 이 이벤트를 이미 처리했는가({@code ce_id}), 어떤 종류의
 * 이벤트인가({@code ce_type}), 같은 대상에 대해 이미 더 최신 것을 반영했는가({@code ce_subject} 와 {@code ce_sequence}).
 */
final class OutboxMessages {

    /** 계정이 삭제됐음을 알리는 topic. 지금 이 서비스가 발행하는 유일한 topic 이고, content 가 구독한다. */
    static final String USER_DELETED = "auth.user.deleted.v1";

    /** 계약의 시각 표기. RFC 3339 UTC 이고 밀리초 세 자리를 항상 적는다. */
    private static final DateTimeFormatter CE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private OutboxMessages() {}

    /**
     * 이벤트 종류가 갈 topic. topic 은 행에 적어 두지 않고 여기서 정한다 — 계약이 새 버전 topic 으로 옮길 때 같은 행을 두 topic 에 보내도록 정해
     * 두었으므로, topic 은 행의 성질이 아니다.
     *
     * @throws IllegalArgumentException 계약에 없는 종류일 때. relay 는 이 행을 격리한다.
     */
    static String topicOf(String eventType) {
        return switch (eventType) {
            case "UserDeleted" -> USER_DELETED;
            default -> throw new IllegalArgumentException("unknown event type: " + eventType);
        };
    }

    /**
     * 행 하나를 topic, key, value, header 를 갖춘 Kafka 레코드로 만든다.
     *
     * @throws IllegalArgumentException 행의 종류가 계약에 없을 때
     */
    static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topicOf(event.eventType()), event.eventKey(), event.payload());
        header(record, "ce_specversion", "1.0");
        header(record, "ce_id", event.id().toString());
        header(record, "ce_type", event.eventType());
        header(record, "ce_source", "/auth");
        // 이벤트의 대상. payload 에도 같은 id 가 있지만, 소비자가 value 를 열지 않고 대상을 알 수 있게 꺼내 싣는다.
        header(record, "ce_subject", event.aggregateId().toString());
        header(record, "ce_time", CE_TIME.format(event.createdAt()));
        // 계약에서는 선택 header 지만 모든 행에 붙인다. 쓰지 않는 소비자는 무시하면 되고, 붙여 두면 같은 대상의
        // 이벤트가 순서가 바뀌어 도착했을 때 어느 쪽이 나중 것인지 가릴 수 있다.
        header(record, "ce_sequence", Long.toString(event.seq()));
        header(record, "content-type", "application/json");
        // 추적을 붙이기 전까지는 값이 없다. 빈 header 를 보내지 않고 아예 붙이지 않는다.
        if (event.traceparent() != null) {
            header(record, "traceparent", event.traceparent());
        }
        return record;
    }

    /** header 하나를 붙인다. Kafka 의 header 값은 글자가 아니라 bytes 라서 UTF-8 로 바꿔 넣는다. */
    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
