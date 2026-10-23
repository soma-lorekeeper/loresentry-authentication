package com.loresentry.authentication.adapter.out.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * outbox_events 테이블을 읽고 쓴다. 쓰는 쪽은 둘이다.
 *
 * <p><b>이벤트를 넣는 쪽</b>은 도메인 변경 트랜잭션(예: persistence 의 계정 삭제)이다. {@link #append(OutboxRecord...)} 하나만
 * 부르고, 패키지 밖에서 부를 수 있는 것도 이 메서드뿐이다. 이벤트의 종류도 payload 의 모양도 모른다 — 받은 {@link OutboxRecord} 를 같은 방법으로
 * 쓴다.
 *
 * <p><b>넣은 행을 보내는 쪽</b>은 relay 다. relay 의 한 회차는 아래 메서드를 이 순서로 부른다.
 *
 * <ol>
 *   <li>{@link #tryRelayLock()} — 이번 회차를 이 인스턴스가 돌려도 되는지 확인한다.
 *   <li>{@link #lockPending(int)} — 보낼 행을 순서대로 읽으면서 잠근다.
 *   <li>{@link #markPublished(List)} — 보낸 행에 발행 표시를 한다.
 *   <li>{@link #markFailed(UUID, String)}, {@link #quarantine(UUID, String)} — 보내지 못한 행에 기록을 남긴다.
 * </ol>
 *
 * <p>전제: 이 메서드들은 한 트랜잭션 안에서 불려야 한다. 1번과 2번의 잠금은 트랜잭션이 끝날 때 풀리므로, 트랜잭션 없이 부르면 SQL 한 줄이 끝나는 순간 풀려
 * 아무것도 지켜 주지 못한다. 트랜잭션은 부르는 쪽({@link OutboxPublisher#publishBatch()})이 연다.
 *
 * <p>이 전제를 코드가 지키도록 위 메서드들에 {@code MANDATORY} 를 붙였다. 이미 열려 있는 트랜잭션에 합류하고, 없으면 SQL 을 실행하기 전에 예외를
 * 던진다. 여기서 직접 트랜잭션을 열지는 않는다 — 열면 메서드마다 따로 열리고 닫혀서, 전제를 어긴 호출이 오류 없이 틀린 동작을 하게 된다.
 *
 * <p>{@link #deletePublishedBefore(OffsetDateTime)} 만 예외다. 회차와 무관하게 정리 작업({@link OutboxPurge})이 하루
 * 한 번 따로 부른다. 잠금에 기대지 않는 SQL 한 줄이라 스스로 트랜잭션을 연다.
 */
@Component
@RequiredArgsConstructor
public class OutboxRepository {

    /**
     * relay 를 한 인스턴스만 돌게 하는 advisory lock 의 번호. DB 는 이 숫자가 무엇을 뜻하는지 모른다 — "relay 를 돌리려면 이 번호를 먼저
     * 잡는다"는 약속을 이 코드가 지킬 뿐이다. 같은 DB 에서 다른 용도가 같은 번호를 쓰면 서로를 막으므로, authentication DB 안에서 겹치지 않아야 한다.
     */
    static final long RELAY_LOCK_KEY = 7_001L;

    /**
     * SQL 을 실행하는 Spring 의 도구. 계정은 JPA 로 다루지만 outbox 는 entity 가 아니라 줄 단위로 잠그고 표시하는 테이블이라 SQL 을 직접
     * 쓴다. JPA 가 연 트랜잭션에 같은 connection 으로 합류한다.
     */
    private final JdbcClient jdbcClient;

    /**
     * {@link #append(OutboxRecord...)} 가 payload 를 JSON 글자로 바꿀 때 쓴다. 앱의 Jackson
     * 설정(JacksonConfiguration)은 HTTP 요청을 읽는 규칙이라 쓰지 않고 따로 만든다 — 이벤트 계약의 모양이 HTTP 설정을 따라 바뀌지 않게 하려는
     * 것이다. 값을 넣어 둔 final 필드라 Lombok 생성자의 인자가 되지 않는다.
     */
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 메시지들을 도메인 변경과 같은 트랜잭션에서 쓴다. 트랜잭션 없이 불리면 실행하기 전에 예외를 던진다({@code MANDATORY}) — 따로 커밋되면 변경 없이
     * 이벤트만 나가거나, 이벤트 없이 변경만 남는다.
     *
     * <p>행마다 payload 를 JSON 으로 바꿔 INSERT 한다. id, seq, created_at 은 DB 가 채우고, seq 는 INSERT 하는 순간
     * 받으므로 넣은 순서가 곧 보낼 순서다.
     *
     * @param outboxRecords 쓸 메시지. 순서대로 쓴다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxRecord... outboxRecords) {
        for (OutboxRecord outboxRecord : outboxRecords) {
            // :payload::jsonb 는 글자로 넘긴 JSON 을 jsonb 컬럼에 맞게 바꾼다.
            jdbcClient
                    .sql(
                            """
                            insert into outbox_events (event_type, aggregate_id, event_key, payload)
                            values (:type, :aggregate, :key, :payload::jsonb)
                            """)
                    .param("type", outboxRecord.eventType())
                    .param("aggregate", outboxRecord.aggregateId())
                    .param("key", outboxRecord.key())
                    .param("payload", json.writeValueAsString(outboxRecord.payload()))
                    .update();
        }
    }

    /**
     * relay 를 돌릴 권리를 얻어 본다. 한 회차를 시작할 때 가장 먼저 부른다.
     *
     * <p>트랜잭션에 묶인 잠금이라 commit 이나 rollback 때 저절로 풀린다. 그래서 풀어 주는 메서드가 따로 없고, pod 가 도중에 죽어도 잠금이 남지
     * 않는다. 못 얻으면 기다리지 않고 false 다 — 다른 인스턴스가 이번 회차를 돌리고 있다는 뜻이므로 이쪽은 그냥 건너뛴다.
     *
     * @return 잠금을 얻었으면 true, 다른 트랜잭션이 이미 잡고 있으면 false
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean tryRelayLock() {
        // pg_try_advisory_xact_lock 은 PostgreSQL 함수다. 이름의 try 는 "기다리지 않는다", xact 는 "트랜잭션이
        // 끝나면 풀린다"는 뜻이다. 결과는 true/false 한 칸이다.
        return jdbcClient
                .sql("select pg_try_advisory_xact_lock(:key)")
                .param("key", RELAY_LOCK_KEY)
                .query(Boolean.class)
                .single();
    }

    /**
     * 아직 보내지 않았고 격리되지도 않은 행을 보낼 순서대로 읽으면서 잠근다.
     *
     * <p>상한은 행 수 하나다. 이 서비스의 이벤트는 id 몇 개가 든 수십 바이트짜리라, 행 수만 끊어도 한 회차가 쥐는 메모리가 정해진다.
     *
     * <p>advisory lock 이 relay 를 하나로 묶어 주므로 행 잠금은 평소에는 할 일이 없다. 그 약속이 깨졌을 때 두 relay 가 같은 행을 함께 보내지
     * 않게 하는 두 번째 장치다.
     *
     * <p>전제: 트랜잭션 안에서 불려야 한다. 트랜잭션 밖이면 행 잠금이 곧바로 풀린다.
     *
     * @param maxRows 읽는 행 수의 상한
     * @return 보낼 순서(seq 가 작은 것부터)대로 담긴 행. 보낼 것이 없으면 빈 목록
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<OutboxEvent> lockPending(int maxRows) {
        // SQL 의 각 부분이 하는 일:
        //   payload::text    jsonb 를 글자로 바꿔 받는다. Kafka 에는 이 글자를 그대로 싣는다.
        //   where            아직 보내지 않았고(published_at), 격리되지도 않은(quarantined_at) 행만 고른다.
        //   order by seq     seq 가 작은 것부터. 이 순서가 곧 Kafka 로 보내는 순서다.
        //   limit            행 수 상한.
        //   for update       읽은 행을 이 트랜잭션이 끝날 때까지 잠근다.
        //   skip locked      다른 트랜잭션이 이미 잠근 행은 기다리지 않고 건너뛴다.
        return jdbcClient
                .sql(
                        """
                        select id, seq, event_type, event_key, aggregate_id, payload::text as payload,
                               created_at, traceparent
                        from outbox_events
                        where published_at is null and quarantined_at is null
                        order by seq
                        limit :maxRows
                        for update skip locked
                        """)
                .param("maxRows", maxRows)
                // 행마다 mapRow 를 불러 목록으로 받는다. 두 번째 인자(몇 번째 행인지)는 쓰지 않는다.
                .query((rows, index) -> mapRow(rows))
                .list();
    }

    /**
     * 보낸 행에 발행 표시를 한다. published_at 이 채워진 행은 {@link #lockPending(int)} 이 다시 고르지 않는다.
     *
     * <p>시각은 DB 의 now() 로 적는다. PostgreSQL 의 now() 는 SQL 이 실행된 시각이 아니라 트랜잭션이 시작된 시각이라, 한 회차에 보낸 행은
     * 모두 같은 값을 받는다.
     *
     * @param ids 발행에 성공한 행의 id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void markPublished(List<UUID> ids) {
        // id = any (:ids) 는 "id 가 배열 :ids 안에 있는 행"이다.
        jdbcClient
                .sql("update outbox_events set published_at = now() where id = any (:ids)")
                // List 를 그대로 넘기면 Spring 이 ?, ?, ? 로 펼쳐 SQL 이 깨진다. 배열로 넘겨야 PostgreSQL 배열 하나로
                // 전달된다.
                .param("ids", ids.toArray(UUID[]::new))
                .update();
    }

    /**
     * 보내지 못한 행에 시도 횟수와 이유를 남긴다. 행은 미발행으로 남아 다음 회차에 다시 나간다.
     *
     * <p>횟수는 격리 판단에 쓰지 않는다. 같은 실패가 이어지고 있다는 것을 사람이 알아채는 단서다.
     *
     * @param id 보내지 못한 행의 id
     * @param error 실패의 이유. 예외의 클래스 이름과 메시지다
     * @return 이번 실패까지 더한 시도 횟수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int markFailed(UUID id, String error) {
        // attempts = attempts + 1 은 DB 가 한 번에 읽고 더한다. 앱이 읽어 와 더한 값을 다시 쓰는 방식과 달리
        // 사이에 다른 변경이 끼어들 틈이 없다. returning 은 더한 뒤의 값을 조회 없이 돌려받는다.
        return jdbcClient
                .sql(
                        """
                        update outbox_events
                        set attempts = attempts + 1, last_error = :error
                        where id = :id
                        returning attempts
                        """)
                .param("error", error)
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    /**
     * 다시 보내도 소용없는 행을 줄에서 뺀다. quarantined_at 이 채워진 행은 {@link #lockPending(int)} 이 고르지 않으므로, 그 뒤의 행이
     * 막히지 않고 나간다.
     *
     * <p>행을 지우지는 않는다. 원인을 고친 뒤 사람이 quarantined_at 을 비우면 다음 회차에 다시 나간다.
     *
     * @param id 격리할 행의 id
     * @param error 격리한 이유. 예외의 클래스 이름과 메시지다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void quarantine(UUID id, String error) {
        // 격리도 한 번의 시도이므로 attempts 를 함께 올린다.
        jdbcClient
                .sql(
                        """
                        update outbox_events
                        set attempts = attempts + 1, last_error = :error, quarantined_at = now()
                        where id = :id
                        """)
                .param("error", error)
                .param("id", id)
                .update();
    }

    /**
     * 발행이 끝난 지 오래된 행을 지운다. relay 는 보낸 행에 발행 표시만 하고 남겨 두므로, 지우지 않으면 테이블이 끝없이 커진다.
     *
     * <p>발행된 행만 대상이다. 아직 보내지 못한 행과 격리된 행은 {@code published_at} 이 비어 있어 조건에 걸리지 않는다 — 격리된 행은 사람이 풀어
     * 줄 때까지 남아 있어야 한다.
     *
     * <p>다른 메서드와 달리 {@code MANDATORY} 가 아니다. 잠금을 트랜잭션 끝까지 들고 있어야 하는 일이 아니라 SQL 한 줄로 끝나므로, 불린 자리에서
     * 트랜잭션을 열고 닫는다.
     *
     * @param cutoff 이 시각보다 먼저 발행된 행을 지운다
     * @return 지운 행의 수
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    int deletePublishedBefore(OffsetDateTime cutoff) {
        return jdbcClient
                .sql("delete from outbox_events where published_at < :cutoff")
                .param("cutoff", cutoff)
                .update();
    }

    /**
     * 결과의 현재 행 하나를 {@link OutboxEvent} 로 옮긴다. 컬럼 이름은 {@link #lockPending(int)} 의 SELECT 와 맞아야 한다.
     */
    private static OutboxEvent mapRow(ResultSet rows) throws SQLException {
        // UUID 와 시각은 ResultSet 에 전용 메서드가 없어서, getObject 에 받을 타입을 알려 준다.
        return new OutboxEvent(
                rows.getObject("id", UUID.class),
                rows.getLong("seq"),
                rows.getString("event_type"),
                rows.getString("event_key"),
                rows.getObject("aggregate_id", UUID.class),
                rows.getString("payload"),
                rows.getObject("created_at", OffsetDateTime.class),
                rows.getString("traceparent"));
    }
}
