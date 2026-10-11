package com.loresentry.authentication.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Kafka 까지 필요한 테스트의 부모. outbox relay 는 outbox(PostgreSQL)를 읽어 Kafka 로 보내므로, Kafka 테스트는 언제나 DB 도 함께
 * 쓴다. 그래서 {@link DatabaseTestSupport} 위에 얹는다.
 *
 * <p>Kafka 가 필요 없는 테스트는 이 클래스를 상속하지 않는다. 컨테이너는 이 클래스가 처음 쓰일 때만 뜬다. 상속하는 클래스는 다른 DB 테스트처럼
 * {@code @SpringBootTest} 를 붙인다.
 */
public abstract class KafkaTestSupport extends DatabaseTestSupport {

    /** 계정 삭제를 알리는 topic. 운영에서는 gitops 의 KafkaTopic 선언이 만든다. */
    protected static final String USER_DELETED_TOPIC = "auth.user.deleted.v1";

    /**
     * 운영 broker 와 같은 버전이다. topic 자동 생성도 운영처럼 끈다 — 켜 두면 topic 이름을 틀려도 새 topic 이 생겨서 테스트가 통과해 버린다.
     *
     * <p>{@code @Container} 를 쓰지 않고 직접 띄운다. {@code @Container} 는 테스트 클래스 하나가 끝나면 컨테이너를 멈추므로, 이 클래스를
     * 상속한 두 번째 테스트 클래스가 멈춘 broker 에 붙으려 한다. JVM 이 끝나면 Testcontainers 가 치운다.
     */
    private static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:4.3.1")
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        KAFKA.start();
        // 운영과 같은 partition 수로 만든다. broker 가 한 대뿐이라 replica 는 1 이다.
        try (Admin admin =
                Admin.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(USER_DELETED_TOPIC, 3, (short) 1))).all().get();
        } catch (Exception failure) {
            throw new IllegalStateException("could not create the test topic", failure);
        }
    }

    /** 컨테이너의 접속 주소는 뜰 때마다 달라진다. 앱이 localhost:9092 대신 이 주소를 쓰게 한다. */
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** outbox 에 행을 넣고 읽는 데 쓴다. outbox 는 entity 가 아니라서 테스트도 SQL 로 다룬다. */
    @Autowired protected JdbcClient jdbcClient;

    /**
     * 테스트 사이에 DB 를 비우지 않으므로, 다른 테스트가 계정을 지우며 남긴 outbox 행이 그대로 있다. relay 는 미발행 행을 가리지 않고 가져가므로 매 테스트
     * 전에 비운다.
     */
    @BeforeEach
    void clearOutbox() {
        jdbcClient.sql("delete from outbox_events").update();
    }

    /**
     * topic 에서 이 key 의 레코드가 {@code count} 개 나올 때까지 읽어, topic 에 실린 순서대로 돌려준다. 컨테이너를 모든 테스트가 같이 쓰므로
     * topic 에는 다른 테스트가 보낸 레코드도 남아 있다. 그래서 "이만큼만 있어야 한다"가 아니라 자기 key 를 찾는다.
     *
     * <p>읽을 때마다 새 group 으로 처음부터 읽는다. group 을 다시 쓰면 앞 테스트가 읽은 위치에서 이어 읽게 되어, 테스트가 도는 순서에 따라 결과가
     * 달라진다. 이 서비스는 구독하는 topic 이 없어 앱에 consumer 설정이 없으므로, 여기서 consumer 를 직접 만든다.
     *
     * @param topic 읽을 topic
     * @param key 찾을 레코드의 key. 테스트마다 새 UUID 를 key 로 써서 서로 겹치지 않게 한다
     * @param count 기다릴 레코드 수
     * @return key 가 맞는 레코드 {@code count} 개
     * @throws AssertionError 20초 안에 {@code count} 개가 모이지 않았을 때
     */
    protected static List<ConsumerRecord<String, String>> awaitRecords(
            String topic, String key, int count) {
        // 지금까지 찾은, key 가 맞는 레코드. key 가 같으면 같은 partition 이라 읽은 순서가 곧 실린 순서다.
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        Map<String, Object> settings =
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "test." + UUID.randomUUID(),
                        // 새 group 은 읽던 위치가 없다. 처음부터 읽어야 먼저 실린 레코드를 놓치지 않는다.
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest",
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class);
        // try-with-resources 라 메서드를 나갈 때 consumer 가 닫힌다.
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(settings)) {
            consumer.subscribe(List.of(topic));
            // 20초 뒤를 마감으로 잡는다. nanoTime 은 흘러간 시간만 재므로 시스템 시계가 바뀌어도 영향받지 않는다.
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                // 최대 0.5초 기다려 새로 도착한 레코드를 받는다. 없으면 빈 묶음이 온다.
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofMillis(500))) {
                    // 다른 테스트가 보낸 레코드는 건너뛴다.
                    if (key.equals(record.key())) {
                        found.add(record);
                        // 다 모였으면 남은 시간을 기다리지 않고 곧바로 돌려준다.
                        if (found.size() == count) {
                            return found;
                        }
                    }
                }
            }
        }
        // 마감까지 다 모이지 않았다. 몇 개까지 왔는지 알려 주며 테스트를 실패시킨다.
        throw new AssertionError(
                "expected "
                        + count
                        + " record(s) with key "
                        + key
                        + " on "
                        + topic
                        + " but found "
                        + found.size());
    }
}
