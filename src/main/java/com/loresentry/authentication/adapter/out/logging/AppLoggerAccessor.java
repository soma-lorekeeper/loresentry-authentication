package com.loresentry.authentication.adapter.out.logging;

import com.loresentry.authentication.logging.AppLogger;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 스레드를 옮길 때 {@link AppLogger}도 함께 옮기는 context-propagation 접근자(LOG_SCHEMA.md 5절 "스레드를 옮기는 곳은 객체를 함께
 * 옮긴다").
 *
 * <p>Google 토큰 교환과 Redis 명령은 시간 제한을 걸려고 가상 스레드 실행기({@link #virtualThreadExecutor()})에서 돈다. 그 실행기는
 * {@code ContextExecutorService}로 감싸여 있어, 맡길 때의 AppLogger와 span(Spring이 등록한 observation 접근자)이 새
 * 스레드로 함께 옮겨 간다. 그래서 그 스레드에서 찍은 줄도 원래 요청의 trace·user를 갖는다.
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
    }

    /**
     * 시간 제한을 걸려고 따로 도는 가상 스레드 실행기(Google 토큰 교환, Redis 세션 명령). {@code ContextExecutorService}로 감싸,
     * 작업을 맡길 때의 AppLogger·span을 새 스레드로 함께 옮긴다. 그래서 그 스레드에서 찍힌 줄(호출 결과 줄 포함)도 원래 요청의 trace·user를
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
}
