package com.loresentry.authentication.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loresentry.authentication.support.DatabaseTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link OutboxRepository#append} 가 이벤트 내용을 모른 채 지키는 약속을 확인한다: 받은 그대로 쓰고, 트랜잭션 밖에서는 쓰지 않는다.
 *
 * <p>outbox_events 는 테스트 사이에 지워지지 않으므로 매번 새 aggregate id 로 거른다. Kafka 는 쓰지 않는다 — 행을 넣기만 하고 보내는 것은
 * relay 의 일이다.
 */
@SpringBootTest
class OutboxAppendTest extends DatabaseTestSupport {

    /** 테스트 대상. */
    @Autowired OutboxRepository outbox;

    /** 코드로 트랜잭션을 여는 도구. 도메인 변경 트랜잭션 대신 append 를 감싼다. */
    @Autowired TransactionTemplate transaction;

    /** 넣은 행을 확인할 때 쓴다. */
    @Autowired JdbcClient jdbcClient;

    /** 저장된 payload 를 글자가 아니라 내용으로 비교하려고 JSON 으로 읽는 도구. */
    private final JsonMapper json = JsonMapper.builder().build();

    /** 아무 모양이나 직렬화되는지 보려는 payload. */
    record Sample(String name) {}

    @Test
    void writesTheRecordAsGiven() {
        UUID aggregate = UUID.randomUUID();

        transaction.executeWithoutResult(
                status ->
                        outbox.append(
                                new OutboxRecord(
                                        "UserDeleted",
                                        aggregate,
                                        "user-key",
                                        new Sample("첫 이벤트"))));

        record Row(String eventType, String eventKey, String payload) {}
        Row row =
                jdbcClient
                        .sql(
                                "select event_type, event_key, payload::text as payload from outbox_events"
                                        + " where aggregate_id = :id")
                        .param("id", aggregate)
                        .query(
                                (rows, index) ->
                                        new Row(
                                                rows.getString("event_type"),
                                                rows.getString("event_key"),
                                                rows.getString("payload")))
                        .single();
        assertThat(row.eventType()).isEqualTo("UserDeleted");
        assertThat(row.eventKey()).isEqualTo("user-key");
        assertThat(json.readTree(row.payload())).isEqualTo(json.readTree("{\"name\":\"첫 이벤트\"}"));
    }

    /** 따로 커밋되면 변경 없이 이벤트만 나가거나 그 반대가 된다. 트랜잭션 없이는 아예 실행하지 않는다. */
    @Test
    void refusesToRunOutsideATransaction() {
        UUID aggregate = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                outbox.append(
                                        new OutboxRecord(
                                                "UserDeleted",
                                                aggregate,
                                                "user-key",
                                                new Sample("x"))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rowsOf(aggregate)).isZero();
    }

    private long rowsOf(UUID aggregate) {
        return jdbcClient
                .sql("select count(*) from outbox_events where aggregate_id = :id")
                .param("id", aggregate)
                .query(Long.class)
                .single();
    }
}
