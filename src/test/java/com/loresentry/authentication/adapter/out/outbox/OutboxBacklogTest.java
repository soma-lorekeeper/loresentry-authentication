package com.loresentry.authentication.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.loresentry.authentication.support.DatabaseTestSupport;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * relay 가 지표로 내보내는 밀림({@link OutboxRepository#backlog()})이 relay 가 보낼 행과 같은 행만 세는지 확인한다. 보낸 행과 격리된
 * 행은 세지 않고, 가장 오래된 생성 시각은 보낼 행 중에서 고른다.
 *
 * <p>Kafka 는 필요 없어서 DB 만 쓰는 {@link DatabaseTestSupport} 위에 얹는다. 행은 SQL 로 직접 넣는다.
 */
@SpringBootTest
class OutboxBacklogTest extends DatabaseTestSupport {

    /** 테스트 대상. Spring 이 만든 것이라 실제 DB 를 읽는다. */
    @Autowired OutboxRepository repository;

    /** outbox 에 행을 넣는 데 쓴다. */
    @Autowired JdbcClient jdbcClient;

    /** 테스트 사이에 DB 를 비우지 않으므로, 다른 테스트가 남긴 outbox 행을 지우고 시작한다. */
    @BeforeEach
    void clearOutbox() {
        jdbcClient.sql("delete from outbox_events").update();
    }

    /**
     * outbox 에 행 하나를 넣는다. 세는 데 쓰는 것은 시각뿐이라 나머지 값은 아무것이나 넣는다.
     *
     * @param createdAt 행이 만들어진 시각
     * @param publishedAt 발행된 시각. 아직 보내지 않은 행이면 null
     * @param quarantinedAt 격리된 시각. 격리되지 않은 행이면 null
     */
    private void insert(
            OffsetDateTime createdAt, OffsetDateTime publishedAt, OffsetDateTime quarantinedAt) {
        jdbcClient
                .sql(
                        """
                        insert into outbox_events
                            (event_type, event_key, aggregate_id, payload, created_at, published_at, quarantined_at)
                        values ('UserDeleted', 'k', uuidv7(), '{}'::jsonb, :created, :published, :quarantined)
                        """)
                .param("created", createdAt)
                .param("published", publishedAt)
                .param("quarantined", quarantinedAt)
                .update();
    }

    /** 보낼 행이 없으면 0건이고 가장 오래된 시각은 없다. */
    @Test
    void countsNothingWhenEverythingWasSent() {
        OffsetDateTime now = OffsetDateTime.now();
        insert(now.minusHours(1), now.minusMinutes(59), null);

        assertThat(repository.backlog()).isEqualTo(Backlog.NONE);
    }

    /** 보내지 않은 행만 세고, 가장 오래된 시각도 그중에서 고른다. 보낸 행과 격리된 행이 더 오래됐어도 고르지 않는다. */
    @Test
    void countsOnlyRowsTheRelayWouldSend() {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        insert(now.minusHours(3), now.minusHours(2), null);
        insert(now.minusHours(2), null, now.minusHours(1));
        insert(now.minusMinutes(10), null, null);
        insert(now.minusMinutes(5), null, null);

        Backlog backlog = repository.backlog();

        assertThat(backlog.pending()).isEqualTo(2);
        assertThat(backlog.oldest().toInstant()).isEqualTo(now.minusMinutes(10).toInstant());
    }
}
