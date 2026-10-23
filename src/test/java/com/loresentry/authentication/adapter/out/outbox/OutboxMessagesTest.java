package com.loresentry.authentication.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

/**
 * outbox 행이 계약이 정한 Kafka 레코드로 바뀌는지 확인한다. Kafka 도 DB 도 Spring 도 쓰지 않는다 — 행을 손으로 만들어 넣고 결과 레코드를 본다.
 *
 * <p>topic 이름과 header 의 값은 main 의 상수를 가져다 쓰지 않고 글자로 적는다. 같은 상수를 쓰면 그 값이 틀려도 테스트가 함께 틀려서 알아채지 못한다.
 */
class OutboxMessagesTest {

    private static final UUID EVENT_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");

    private static final UUID USER_ID = UUID.fromString("0199a1b2-0000-7000-8000-000000000001");

    /** 계정 삭제가 넣는 것과 같은 모양의 행. key 와 대상이 모두 사용자 id 다. */
    private static OutboxEvent event(String eventType, String createdAt, String traceparent) {
        return new OutboxEvent(
                EVENT_ID,
                1042,
                eventType,
                USER_ID.toString(),
                USER_ID,
                "{\"user_id\":\"" + USER_ID + "\"}",
                OffsetDateTime.parse(createdAt),
                traceparent);
    }

    private static OutboxEvent event(String eventType) {
        return event(eventType, "2026-09-30T10:00:00.123Z", null);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @Test
    void sendsAccountDeletionsToTheirTopic() {
        assertThat(OutboxMessages.topicOf("UserDeleted")).isEqualTo("auth.user.deleted.v1");
    }

    /** 모르는 종류를 아무 topic 으로나 보내면 소비자가 받아서는 안 될 것을 받는다. 고르지 않고 멈춘다. relay 는 이 행을 격리한다. */
    @Test
    void refusesAnEventTypeTheContractDoesNotName() {
        assertThatThrownBy(() -> OutboxMessages.topicOf("UserRestored"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxMessages.toRecord(event("UserRestored")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsTheRecordTheContractDescribes() {
        ProducerRecord<String, String> record = OutboxMessages.toRecord(event("UserDeleted"));

        assertThat(record.topic()).isEqualTo("auth.user.deleted.v1");
        assertThat(record.key()).isEqualTo(USER_ID.toString());
        // value 에는 이벤트 데이터만 있다. 이메일이나 이름 같은 개인정보는 싣지 않는다.
        assertThat(record.value()).isEqualTo("{\"user_id\":\"" + USER_ID + "\"}");
        // partition 은 비워 둔다. 비어 있어야 key 로 정해지고, 같은 사용자가 같은 partition 으로 간다.
        assertThat(record.partition()).isNull();

        assertThat(header(record, "ce_specversion")).isEqualTo("1.0");
        assertThat(header(record, "ce_id")).isEqualTo(EVENT_ID.toString());
        assertThat(header(record, "ce_type")).isEqualTo("UserDeleted");
        assertThat(header(record, "ce_source")).isEqualTo("/auth");
        assertThat(header(record, "ce_subject")).isEqualTo(USER_ID.toString());
        assertThat(header(record, "ce_time")).isEqualTo("2026-09-30T10:00:00.123Z");
        assertThat(header(record, "content-type")).isEqualTo("application/json");
    }

    /** 계약에서는 선택 header 지만 붙여 보낸다. 값은 outbox 행의 seq 다. */
    @Test
    void carriesTheOutboxSequence() {
        assertThat(header(OutboxMessages.toRecord(event("UserDeleted")), "ce_sequence"))
                .isEqualTo("1042");
    }

    /** DB 의 시각은 마이크로초까지 있고 시간대가 붙어 온다. 계약은 UTC 에 밀리초 세 자리다. */
    @Test
    void writesTheTimeInUtcWithExactlyThreeFractionDigits() {
        assertThat(
                        header(
                                OutboxMessages.toRecord(
                                        event(
                                                "UserDeleted",
                                                "2026-09-30T19:00:00.123456+09:00",
                                                null)),
                                "ce_time"))
                .isEqualTo("2026-09-30T10:00:00.123Z");
        // 밀리초가 0 이어도 자리를 지운 채로 보내지 않는다.
        assertThat(
                        header(
                                OutboxMessages.toRecord(
                                        event("UserDeleted", "2026-09-30T10:00:00Z", null)),
                                "ce_time"))
                .isEqualTo("2026-09-30T10:00:00.000Z");
    }

    /** 추적 정보는 행에 있을 때만 싣는다. 값이 없는데 빈 header 를 붙이지 않는다. */
    @Test
    void addsTheTraceHeaderOnlyWhenTheRowHasOne() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        assertThat(
                        OutboxMessages.toRecord(event("UserDeleted"))
                                .headers()
                                .lastHeader("traceparent"))
                .isNull();
        assertThat(
                        header(
                                OutboxMessages.toRecord(
                                        event(
                                                "UserDeleted",
                                                "2026-09-30T10:00:00.123Z",
                                                traceparent)),
                                "traceparent"))
                .isEqualTo(traceparent);
    }
}
