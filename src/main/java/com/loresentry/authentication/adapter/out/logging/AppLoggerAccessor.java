package com.loresentry.authentication.adapter.out.logging;

import com.loresentry.authentication.logging.AppLogger;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.ThreadLocalAccessor;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 스레드를 옮길 때 {@link AppLogger}도 함께 옮기는 context-propagation 접근자(LOG_SCHEMA.md 5절 "스레드를 옮기는 곳은 객체를 함께
 * 옮긴다").
 *
 * <p>Google 토큰 교환과 Redis 명령은 시간 제한을 걸려고 가상 스레드 실행기({@link #virtualThreadExecutor()})에서 돈다. 그 실행기는
 * {@code ContextExecutorService}로 감싸여 있어, 맡길 때의 AppLogger와 OpenTelemetry 문맥({@link
 * SpanContextAccessor}, 지금 span)이 새 스레드로 함께 옮겨 간다. 그래서 그 스레드에서 찍은 줄도 원래 요청의 trace·user를 갖고, 그 스레드에서
 * 계측이 연 span(Google HTTP, Redis 명령)도 맡긴 쪽의 span({@code google call}·{@code redis call}) 아래에 붙는다.
 * agent의 executors 계측도 문맥을 옮기지만, agent가 없는 테스트에서도 같게 동작하도록 직접 옮긴다(둘 다 옮겨도 같은 문맥이라 결과가 같다).
 */
public final class AppLoggerAccessor implements ThreadLocalAccessor<AppLogger> {
    /** context-propagation에 등록하는 이름. */
    static final String KEY = "loresentry.app-logger";

    /** setValue로 새 스레드에 바인딩한 범위. restore에서 닫아 그 스레드를 원래대로 되돌린다. */
    private final ThreadLocal<AppLogger.Binding> binding = new ThreadLocal<>();

    /** context-propagation에 이 접근자를 등록한다. 여러 번 불러도 한 번만 등록된다. */
    public static void register() {
        var registry = ContextRegistry.getInstance();
        boolean registered =
                registry.getThreadLocalAccessors().stream().anyMatch(a -> KEY.equals(a.key()));
        if (!registered) registry.registerThreadLocalAccessor(new AppLoggerAccessor());
        boolean spans =
                registry.getThreadLocalAccessors().stream()
                        .anyMatch(a -> SpanContextAccessor.KEY.equals(a.key()));
        if (!spans) registry.registerThreadLocalAccessor(new SpanContextAccessor());
    }

    /**
     * 시간 제한을 걸려고 따로 도는 가상 스레드 실행기(Google 토큰 교환, Redis 세션 명령). {@code ContextExecutorService}로 감싸,
     * 작업을 맡길 때의 AppLogger와 지금 span을 새 스레드로 함께 옮긴다. 그래서 그 스레드에서 찍힌 줄(호출 결과 줄 포함)도 원래 요청의 trace·user를
     * 갖는다. 스레드를 옮기는 실행기도 로깅 팩토리가 만든다(LOG_SCHEMA.md 5절 "만들기"). Spring 없이 만드는 테스트에서도 옮겨지도록 이 접근자를 먼저
     * 등록한다.
     */
    public static ExecutorService virtualThreadExecutor() {
        register();
        return ContextExecutorService.wrap(
                Executors.newVirtualThreadPerTaskExecutor(),
                () -> ContextSnapshotFactory.builder().build().captureAll());
    }

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public AppLogger getValue() {
        // 바인딩이 없으면 기본 객체(trace 없음)다. 새 스레드에 그대로 옮겨도 바인딩이 없는 것과 같다
        return AppLogger.current();
    }

    @Override
    public void setValue(AppLogger value) {
        binding.set(AppLogger.bind(value));
    }

    @Override
    public void setValue() {
        restore();
    }

    /** 작업이 끝나 그 스레드를 맡기기 전 상태로 되돌린다. 바인딩을 닫으면 이전 객체와 MDC 값이 돌아온다. */
    @Override
    public void restore(AppLogger previousValue) {
        restore();
    }

    @Override
    public void restore() {
        AppLogger.Binding bound = binding.get();
        if (bound != null) bound.close();
        binding.remove();
    }

    /**
     * 지금 OpenTelemetry 문맥(지금 span)을 새 스레드로 옮기는 context-propagation 접근자. 맡길 때의 문맥을 새 스레드에서 지금 문맥으로
     * 두고, 작업이 끝나면 그 스레드를 원래대로 되돌린다. 문맥을 지금 문맥으로 두는 일은 스레드마다 연 반대 순서로 닫아야 해서, 연 범위를 스레드별로 기억한다.
     */
    static final class SpanContextAccessor implements ThreadLocalAccessor<Context> {
        /** context-propagation에 등록하는 이름. */
        static final String KEY = "loresentry.otel-context";

        /** setValue로 새 스레드에서 연 범위. restore에서 닫는다. */
        private final ThreadLocal<Scope> scope = new ThreadLocal<>();

        @Override
        public Object key() {
            return KEY;
        }

        @Override
        public Context getValue() {
            return Context.current();
        }

        @Override
        public void setValue(Context value) {
            scope.set(value.makeCurrent());
        }

        /** 맡길 때 문맥이 없었다. 새 스레드의 문맥을 그대로 둔다. */
        @Override
        public void setValue() {}

        @Override
        public void restore(Context previousValue) {
            restore();
        }

        @Override
        public void restore() {
            Scope opened = scope.get();
            if (opened != null) opened.close();
            scope.remove();
        }
    }
}
