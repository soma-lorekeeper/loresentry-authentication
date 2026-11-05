package com.loresentry.authentication.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loresentry.authentication.adapter.out.logging.OutboxMetrics;
import com.loresentry.authentication.adapter.out.logging.TestMeters;
import com.loresentry.authentication.adapter.out.outbox.Round.Outcome;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.support.Slf4jLogSinkExtension;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * relay 가 언제 돌고 언제 쉬는지를 확인한다. 회차가 실제로 무엇을 하는지는 {@link OutboxPublisherTest} 가 보므로, 여기서는 DB 와 Kafka
 * 없이 가짜 회차와 가짜 시계만으로 {@link OutboxRelay} 의 판단을 본다.
 *
 * <p>가짜 회차는 미리 짜 둔 결과를 차례로 돌려주는 함수다. 가짜 시계는 {@link #now} 를 읽기만 하므로, 테스트가 그 값을 바꾸면 시간이 흐른 것이 된다. 실제로
 * 기다리지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
@ExtendWith(Slf4jLogSinkExtension.class)
class OutboxRelayTest {

    /** 할 일이 없었던 회차. */
    private static final Round IDLE = new Round(Outcome.IDLE, 0, 0, 0);

    /** 가짜 시계가 알려 주는 지금 시각. 테스트가 직접 앞당긴다. */
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    /** 가짜 회차가 불린 횟수. relay 가 회차를 돌았는지, 쉬느라 건너뛰었는지를 이 값으로 가린다. */
    private int calls;

    /** 테스트를 도는 스레드. relay 를 직접 부르므로 relay 의 줄은 이 스레드에서 찍힌다. 다른 스레드의 줄은 모으지 않는다. */
    private Thread testThread;

    /** 이 테스트가 찍힌 줄을 모아 보는 appender. 문구와 필드(key-value)를 함께 본다. */
    private final ListAppender<ILoggingEvent> events =
            new ListAppender<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    if (Thread.currentThread() == testThread) super.append(event);
                }
            };

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void attach() {
        testThread = Thread.currentThread();
        events.start();
        root.addAppender(events);
    }

    @AfterEach
    void detach() {
        root.detachAppender(events);
    }

    /** relay 회차가 남긴 줄(작업 이름 outbox-relay 가 붙은 줄)의 문구. */
    private List<String> relayMessages() {
        return events.list.stream()
                .filter(
                        event ->
                                "outbox-relay"
                                        .equals(fields(event).get("loresentry.schedule.name")))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 그 문구로 남은 첫 줄. */
    private ILoggingEvent line(String message) {
        return events.list.stream()
                .filter(event -> message.equals(event.getFormattedMessage()))
                .findFirst()
                .orElseThrow();
    }

    /** 한 줄의 필드(key-value). 같은 이름은 먼저 넣은 값이 남는다(EcsLogFormatter와 같다). */
    private static Map<String, Object> fields(ILoggingEvent event) {
        Map<String, Object> out = new HashMap<>();
        if (event.getKeyValuePairs() != null)
            event.getKeyValuePairs().forEach(pair -> out.putIfAbsent(pair.key, pair.value));
        return out;
    }

    /** {@code published} 개를 보내고 문제없이 끝난 회차. */
    private static Round more(int published) {
        return new Round(Outcome.MORE, published, 0, 0);
    }

    /** {@code failed} 개를 보내지 못해 다시 보내야 하는 회차. */
    private static Round failed(int failed) {
        return new Round(Outcome.FAILED, 0, 0, failed);
    }

    /**
     * 넘겨받은 결과를 불릴 때마다 하나씩 차례로 돌려주는 가짜 회차. 불린 횟수도 센다. 실제 회차({@link
     * OutboxPublisher#publishBatch()})처럼 보낼 행이 있던 회차는 시작 줄(outbox relay batch start)을 남긴다. 그 줄이
     * relay 회차의 첫 줄이다.
     */
    private Supplier<Round> scripted(Round... results) {
        Iterator<Round> remaining = List.of(results).iterator();
        return () -> {
            calls++;
            Round next = remaining.next();
            if (next.outcome() != Outcome.IDLE)
                AppLogger.current().info("outbox relay batch start");
            return next;
        };
    }

    /** 가짜 회차와 가짜 시계를 끼운 relay. */
    private OutboxRelay relay(Supplier<Round> round) {
        return new OutboxRelay(round, () -> now);
    }

    /** 밀린 행은 주기를 기다리지 않고 비운다. 한 번 불렸을 때 "더 있다"가 끝날 때까지 이어서 돈다. */
    @Test
    void keepsGoingWhileThereIsMore() {
        OutboxRelay relay = relay(scripted(more(50), more(20), IDLE));

        relay.run();

        assertThat(calls).isEqualTo(3);
    }

    /** relay 의 지표를 읽어 가는 시험용 reader. 테스트마다 새로 만들어, 다른 테스트의 relay 가 등록한 gauge 가 섞이지 않는다. */
    private final InMemoryMetricReader metricReader = InMemoryMetricReader.create();

    /** 시험용 reader 에 gauge 를 등록하고 가짜 시계로 나이를 재는 지표 객체. */
    private OutboxMetrics testMetrics() {
        return TestMeters.outbox(
                SdkMeterProvider.builder().registerMetricReader(metricReader).build().get("test"),
                () -> now);
    }

    /** 지금 내보내는 그 이름의 gauge 값. 없으면 null. */
    private Double gauge(String name) {
        return metricReader.collectAllMetrics().stream()
                .filter(metric -> name.equals(metric.getName()))
                .map(OutboxRelayTest::firstValue)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** gauge의 첫 점의 값. 정수 gauge와 실수 gauge를 모두 읽는다. */
    private static Double firstValue(MetricData metric) {
        if (!metric.getLongGaugeData().getPoints().isEmpty())
            return (double) metric.getLongGaugeData().getPoints().iterator().next().getValue();
        if (!metric.getDoubleGaugeData().getPoints().isEmpty())
            return metric.getDoubleGaugeData().getPoints().iterator().next().getValue();
        return null;
    }

    /**
     * 밀린 행은 15초마다 회차를 다 돈 뒤에 세어 지표로 넘기고, "relay 가 돌았다"는 불릴 때마다 넘긴다. 쉬는 중이라 회차를 돌지 않아도 relay 가 불렸다는
     * 사실은 남는다(스케줄러가 살아 있다).
     */
    @Test
    void reportsTheBacklogEveryFifteenSecondsAndEveryRun() {
        AtomicInteger counted = new AtomicInteger();
        OffsetDateTime oldest = OffsetDateTime.ofInstant(now.minusSeconds(30), ZoneOffset.UTC);
        OutboxRelay relay =
                new OutboxRelay(
                        scripted(IDLE, IDLE, IDLE),
                        () -> {
                            counted.incrementAndGet();
                            return new Backlog(2, oldest);
                        },
                        testMetrics(),
                        () -> now);

        relay.run();
        now = now.plusSeconds(5);
        relay.run();

        // 처음 회차에서 한 번 세고, 5초 뒤에는 아직 세지 않는다
        assertThat(counted).hasValue(1);
        assertThat(gauge("loresentry.outbox.pending")).isEqualTo(2.0);
        assertThat(gauge("loresentry.outbox.oldest_age")).isEqualTo(35.0);
        assertThat(gauge("loresentry.outbox.relay.last_run_age")).isEqualTo(0.0);

        now = now.plusSeconds(10);
        relay.run();

        assertThat(counted).hasValue(2);
    }

    /** 세다가 실패해도(DB 에 닿지 못함) relay 는 계속 돌고 줄을 남기지 않는다. 지표는 마지막 값을 그대로 내보낸다. */
    @Test
    void keepsRunningQuietlyWhenTheBacklogCannotBeCounted() {
        OutboxRelay relay =
                new OutboxRelay(
                        scripted(IDLE),
                        () -> {
                            throw new IllegalStateException("database unreachable");
                        },
                        testMetrics(),
                        () -> now);

        relay.run();

        assertThat(relayMessages()).isEmpty();
        assertThat(gauge("loresentry.outbox.relay.last_run_age")).isEqualTo(0.0);
    }

    /** 실패가 이어지면 쉬는 시간이 1, 2, 4, 8초로 늘다가 10초에서 멈춘다. 쉬는 동안에는 불려도 회차를 돌지 않는다. */
    @Test
    void restsAfterAFailureAndDoublesTheRestUpToTheLimit() {
        OutboxRelay relay =
                relay(
                        () -> {
                            calls++;
                            return failed(1);
                        });

        // 첫 실패. 여기서부터 쉬기 시작한다.
        relay.run();
        assertThat(calls).isEqualTo(1);

        for (long seconds : new long[] {1, 2, 4, 8, 10, 10}) {
            int before = calls;

            // 쉬는 시간이 끝나기 1밀리초 전에는 불려도 돌지 않는다.
            now = now.plusSeconds(seconds).minusMillis(1);
            relay.run();
            assertThat(calls).isEqualTo(before);

            // 쉬는 시간이 끝나면 다시 시도한다. 또 실패하므로 다음 쉬는 시간이 정해진다.
            now = now.plusMillis(1);
            relay.run();
            assertThat(calls).isEqualTo(before + 1);
        }
    }

    /** 문제가 풀리면 천천히 돌아가지 않는다. 실패가 아닌 결과가 한 번 나오면 다음 실패는 다시 1초부터 쉰다. */
    @Test
    void goesBackToNormalAsSoonAsARoundDoesNotFail() {
        OutboxRelay relay = relay(scripted(failed(1), failed(1), IDLE, failed(1), IDLE));

        // 실패 → 1초 쉼.
        relay.run();
        // 다시 실패 → 2초 쉼.
        now = now.plusSeconds(1);
        relay.run();
        // 이번에는 실패가 아니다 → 평소 상태로.
        now = now.plusSeconds(2);
        relay.run();
        // 쉬는 중이 아니므로 곧바로 돈다. 실패 → 4초가 아니라 다시 1초부터 쉰다.
        relay.run();
        assertThat(calls).isEqualTo(4);

        now = now.plusMillis(999);
        relay.run();
        assertThat(calls).isEqualTo(4);

        now = now.plusMillis(1);
        relay.run();
        assertThat(calls).isEqualTo(5);
    }

    /**
     * 종료가 시작되면 지금 회차까지만 하고 멈춘다. 가짜 회차는 끝없이 "더 있다"고 하므로, 표시를 보지 않으면 이 호출은 끝나지 않는다. 그 경우 테스트가 멈춰 있지
     * 않고 실패하도록 시간 제한을 둔다.
     */
    @Test
    @Timeout(10)
    void stopsBetweenRoundsOnceTheApplicationStartsClosing() {
        // 람다 안에서 relay 자신을 불러야 해서, 나중에 채울 수 있는 칸에 담는다.
        OutboxRelay[] holder = new OutboxRelay[1];
        holder[0] =
                relay(
                        () -> {
                            calls++;
                            if (calls == 3) {
                                // 세 번째 회차가 도는 도중에 종료가 시작됐다.
                                holder[0].stop();
                            }
                            return more(50);
                        });

        holder[0].run();

        // 세 번째 회차는 끝까지 마쳤고, 네 번째는 시작하지 않았다.
        assertThat(calls).isEqualTo(3);
    }

    /** 회차가 예외로 끝나도(예: DB 에 닿지 못함) relay 는 그것을 밖으로 내보내지 않는다. 실패한 회차처럼 쉬고, 예외의 종류만 한 줄 남긴다. */
    @Test
    void treatsAnUnexpectedExceptionLikeAFailure(CapturedOutput output) {
        OutboxRelay relay =
                relay(
                        () -> {
                            calls++;
                            throw new IllegalStateException("database is down");
                        });

        // 예외가 여기까지 올라오지 않는다.
        relay.run();

        // 운영에서 이 문구로 찾는다. 예외의 메시지는 로그에 적지 않는다. 회차의 종료 줄은 실패(warn, OUTBOX_SEND_FAILED)다
        assertThat(relayMessages())
                .containsExactly(
                        "schedule run start", "outbox relay round failed", "schedule run failed");
        assertThat(fields(line("outbox relay round failed")))
                .containsEntry("error.type", "java.lang.IllegalStateException")
                .containsKey("error.stack_trace");
        assertThat(line("outbox relay round failed").getThrowableProxy()).isNull();
        assertThat(line("schedule run failed").getLevel().toString()).isEqualTo("WARN");
        assertThat(fields(line("schedule run failed")))
                .containsEntry("error.type", OutboxRelay.RELAY_FAILED)
                .containsEntry("loresentry.result.backoff_seconds", 1L);
        assertThat(output.getOut()).doesNotContain("database is down");

        // 실패와 같이 1초를 쉰다.
        now = now.plusMillis(999);
        relay.run();
        assertThat(calls).isEqualTo(1);

        now = now.plusMillis(1);
        relay.run();
        assertThat(calls).isEqualTo(2);
    }

    /**
     * 종료 줄은 여러 회차의 개수를 더해 결과 값으로 남긴다. 한 일이 없으면 시작·종료 줄 모두 남기지 않는다 — 주기마다 불리므로 매번 남기면 로그가 "0건"으로 가득
     * 찬다.
     */
    @Test
    void leavesStartAndEndLinesOnlyWhenSomethingWasDone() {
        OutboxRelay relay =
                relay(scripted(more(50), more(50), new Round(Outcome.MORE, 20, 1, 0), IDLE, IDLE));

        // 회차 넷(셋은 보냈고 마지막은 빈 회차)의 합계가 종료 줄 하나에 실린다.
        relay.run();
        assertThat(relayMessages())
                .containsExactly(
                        "schedule run start",
                        "outbox relay batch start",
                        "outbox relay batch start",
                        "outbox relay batch start",
                        "schedule run completed");
        assertThat(fields(line("schedule run completed")))
                .containsEntry("loresentry.result.published", 120)
                .containsEntry("loresentry.result.quarantined", 1)
                .containsEntry("loresentry.result.failed", 0)
                .containsEntry("loresentry.result.rounds", 4)
                .containsKey("event.duration")
                .doesNotContainKey("error.type");
        // 시작·종료 줄의 위치는 회차를 연 relay 메서드다
        assertThat(fields(line("schedule run start")))
                .containsEntry("log.layer", "scheduler")
                .containsEntry("log.origin.function", "run");

        // 다음 호출은 할 일이 없었다. 줄이 더 늘지 않는다.
        int before = events.list.size();
        relay.run();
        assertThat(events.list).hasSize(before);
    }

    /** 실패로 간격을 늘렸을 때는 종료 줄이 실패(warn)이고 그 간격도 적는다. 로그만 보고 relay 가 언제 다시 시도할지 알 수 있다. */
    @Test
    void endsAFailedRoundWithAWarningAndTheBackoff() {
        OutboxRelay relay = relay(scripted(new Round(Outcome.FAILED, 30, 0, 20)));

        relay.run();

        ILoggingEvent end = line("schedule run failed");
        assertThat(end.getLevel().toString()).isEqualTo("WARN");
        assertThat(fields(end))
                .containsEntry("error.type", "OUTBOX_SEND_FAILED")
                .containsEntry("loresentry.result.published", 30)
                .containsEntry("loresentry.result.failed", 20)
                .containsEntry("loresentry.result.backoff_seconds", 1L);
    }

    /**
     * 위의 테스트들은 {@code stop()} 을 직접 불렀다. 이 테스트는 실제 Spring 의 종료 절차가 그것을 제때 부르는지 본다. 작은 앱을 띄워 끝없이 밀린
     * 상태로 relay 를 돌리다가 앱을 닫는다.
     *
     * <p>Spring 은 닫을 때 돌고 있는 스케줄 작업이 끝나기를 기다린다. 종료 표시가 제때 켜지면 relay 는 지금 회차만 마치고 끝나므로 곧 닫힌다. 켜지지
     * 않으면 relay 가 끝나지 않아 Spring 이 한도(기본 30초)까지 기다린다.
     */
    @Test
    @Timeout(60)
    void closingTheApplicationStopsTheRelayWithoutWaitingForTheBacklog() throws Exception {
        // DB 와 Kafka 없이 스케줄링에 필요한 것만 띄운다.
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(ClosingConfig.class)
                        .web(WebApplicationType.NONE)
                        .run();
        EndlessBacklog backlog = context.getBean(EndlessBacklog.class);
        // relay 가 회차를 연달아 돌기 시작할 때까지 기다린다.
        assertThat(backlog.started.await(10, TimeUnit.SECONDS)).isTrue();

        long before = System.nanoTime();
        context.close();
        Duration closing = Duration.ofNanos(System.nanoTime() - before);
        int roundsAtClose = backlog.rounds.get();

        // 한도인 30초를 기다리지 않고 닫혔다.
        assertThat(closing).isLessThan(Duration.ofSeconds(5));
        // 닫힌 뒤에는 회차가 더 돌지 않는다.
        Thread.sleep(300);
        assertThat(backlog.rounds.get()).isEqualTo(roundsAtClose);
    }

    /**
     * 종료 테스트가 띄우는 작은 앱의 구성. 스케줄링을 켜고, Spring Boot 가 스케줄러와 종료 한도를 준비하게 한다.
     *
     * <p>relay 를 {@code @Bean} 으로 직접 만들므로 {@code kafka.producer.relay.enabled} 의 값과 무관하게 만들어진다.
     */
    @Configuration
    @EnableScheduling
    @ImportAutoConfiguration({
        LifecycleAutoConfiguration.class,
        TaskExecutionAutoConfiguration.class,
        TaskSchedulingAutoConfiguration.class
    })
    static class ClosingConfig {

        @Bean
        EndlessBacklog backlog() {
            return new EndlessBacklog();
        }

        @Bean
        OutboxRelay relay(EndlessBacklog backlog) {
            return new OutboxRelay(backlog, InstantSource.system());
        }
    }

    /** 끝없이 "더 있다"고 답하는 가짜 회차. 밀린 행이 아무리 비워도 줄지 않는 상황을 흉내 낸다. */
    static class EndlessBacklog implements Supplier<Round> {

        /** 지금까지 돈 회차 수. */
        final AtomicInteger rounds = new AtomicInteger();

        /** 회차가 세 번 돌면 풀린다. 테스트가 "연달아 돌기 시작했다"를 아는 데 쓴다. */
        final CountDownLatch started = new CountDownLatch(3);

        @Override
        public Round get() {
            try {
                // 회차 하나가 걸리는 시간을 흉내 낸다.
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                // 종료 표시가 듣지 않아 Spring 이 한도 뒤에 끊은 경우다. 반복을 끝내 thread 가 남지 않게 한다.
                return new Round(Outcome.FAILED, 0, 0, 0);
            }
            rounds.incrementAndGet();
            started.countDown();
            return new Round(Outcome.MORE, 1, 0, 0);
        }
    }
}
