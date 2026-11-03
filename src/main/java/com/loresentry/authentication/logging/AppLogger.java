package com.loresentry.authentication.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서비스 코드가 로그를 남기는 유일한 통로다(LOG_SCHEMA.md 5절). 어느 계층이든 {@code AppLogger.current().info(...)}로 찍는다.
 * gateway·content의 같은 이름 클래스와 같은 규칙·API다.
 *
 * <p>흐름
 *
 * <ol>
 *   <li>시작점(HTTP 요청은 RequestLogFilter)이 그때 아는 값(traceId, spanId, userId)을 생성자 인자로 넘겨 만든다.
 *   <li>{@link #bind(AppLogger)}로 요청 범위 저장소(ThreadLocal)에 넣는다. 같은 요청의 어느 계층이든 {@link #current()}로
 *       꺼낸다.
 *   <li>레벨은 info·warn·error만 있다. 필드는 {@link LogField}의 정해 둔 이름만 받으므로 이메일·세션 ID 같은 값이 들어갈 자리가 없다.
 * </ol>
 *
 * <p>이 클래스는 순수 Java다. core(service)가 쓰고, ArchitectureTest가 core에 라이브러리 의존을 허용하지 않기 때문이다. 줄의 위치를
 * StackWalker로 찾아 {@code log.origin.function}·{@code log.layer}·{@code log.package}를 채운 한 줄({@link
 * LogLine})을 만들고, 출력은 {@link LogSink}에 맡긴다. sink(adapter의 SLF4J 구현)는 앱이 시작할 때 config가 {@link
 * #install(LogSink)}로 꽂는다.
 *
 * <p>객체는 바꿀 수 없다. 나중에 아는 값은 {@link #withUser(UUID)}·{@link #with(LogField...)}가 만든 새 객체로 찍거나 다시
 * {@link #bind(AppLogger)}한다. 예외는 결과 필드 칸 하나다: 요청 하나의 객체들이 같은 칸을 함께 쓰고, 실패 이유를 {@link
 * #addToOutcome(LogField...)}로 맡기면 결과 줄(request completed)에 붙는다.
 */
public final class AppLogger {
    /** 같은 스레드의 프레임워크 줄에도 붙이는 문맥 키(SLF4J MDC). EcsLogFormatter가 같은 이름의 ECS 필드로 옮긴다. */
    public static final String TRACE_ID = "trace.id";

    public static final String SPAN_ID = "span.id";
    public static final String USER_ID = "user.id";

    /**
     * 줄을 실제로 출력하는 곳. 앱이 시작할 때 config(LoggingConfiguration)가 adapter의 구현을 {@link #install(LogSink)}로
     * 꽂는다. 꽂기 전에는 아무것도 출력하지 않는다. 요청을 처리하는 스레드들이 함께 읽으므로 volatile 이다.
     */
    private static volatile LogSink sink = LogSink.NONE;

    private static final ThreadLocal<AppLogger> CURRENT = new ThreadLocal<>();

    /** 바인딩이 없을 때(기동, 스케줄 작업, 프레임워크 콜백) 쓰는 객체. trace는 그때 열려 있는 span이 있으면 그 값이 붙는다. */
    private static final AppLogger DETACHED =
            new AppLogger(null, null, null, List.of(), null, List.of());

    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /**
     * javac가 람다 본문으로 만드는 메서드 이름: lambda$<람다를 감싼 메서드>$<번호>(예: lambda$send$3). 람다 안에서 찍은 줄의
     * log.origin.function에는 가운데의 감싼 메서드 이름을 넣는다.
     */
    private static final Pattern LAMBDA_METHOD = Pattern.compile("lambda\\$(.+)\\$\\d+");

    /** 클래스 이름 끝 → log.layer. 위에서부터 맞춰 본다(LOG_SCHEMA.md 5절 표와 같다). */
    private static final List<Map.Entry<String, String>> LAYER_SUFFIXES =
            List.of(
                    Map.entry("Controller", "controller"),
                    Map.entry("Service", "service"),
                    Map.entry("Repository", "repository"),
                    Map.entry("Store", "repository"),
                    Map.entry("Client", "client"),
                    Map.entry("Listener", "consumer"),
                    Map.entry("Consumer", "consumer"),
                    Map.entry("Publisher", "publisher"),
                    Map.entry("Filter", "filter"),
                    Map.entry("Handler", "handler"),
                    Map.entry("Interceptor", "interceptor"));

    /** 클래스별 {log.layer, log.package}. 줄마다 annotation·이름을 다시 보지 않게 클래스 단위로 한 번만 계산한다. */
    private static final ClassValue<String[]> LOCATION =
            new ClassValue<>() {
                @Override
                protected String[] computeValue(Class<?> type) {
                    return new String[] {layerOf(type), type.getPackageName()};
                }
            };

    private final String traceId;
    private final String spanId;
    private final String userId;

    /** 이 객체로 찍는 모든 줄에 붙는 필드(예: 요청 하나의 url.path·http.route). */
    private final List<LogField> context;

    /** 결과 줄(request completed)에 붙일 필드. 요청 하나에서 만든 객체가 같은 목록을 함께 쓴다. 기본 객체는 null이라 쌓이지 않는다. */
    private final List<LogField> outcome;

    /** 이 객체로 찍는 모든 줄의 tags. 정해 둔 이름만 쓴다(LOG_SCHEMA.md 2.3절). */
    private final List<String> tags;

    /**
     * 요청·flow 시작점에서 만든다.
     *
     * @param traceId 추적 라이브러리가 만든 현재 trace ID(32 hex). 없으면 null
     * @param spanId 현재 span ID(16 hex). 없으면 null
     * @param userId auth {@code users.id}. 모르면 null
     */
    public AppLogger(String traceId, String spanId, UUID userId) {
        this(
                traceId,
                spanId,
                userId == null ? null : userId.toString(),
                List.of(),
                new CopyOnWriteArrayList<>(),
                List.of());
    }

    private AppLogger(
            String traceId,
            String spanId,
            String userId,
            List<LogField> context,
            List<LogField> outcome,
            List<String> tags) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.userId = userId;
        this.context = context;
        this.outcome = outcome;
        this.tags = tags;
    }

    /**
     * 줄을 출력할 구현을 꽂는다. core가 adapter를 모르도록, 구현은 바깥(config)이 만들어 넘긴다. 마지막에 꽂은 것이 쓰인다(테스트처럼 Spring
     * context가 여럿이어도 구현이 모두 같아 결과가 같다).
     */
    public static void install(LogSink implementation) {
        sink = implementation == null ? LogSink.NONE : implementation;
    }

    /** 지금 스레드에 바인딩된 객체. 없으면 trace 없는 기본 객체를 돌려주므로 null이 아니다. */
    public static AppLogger current() {
        AppLogger bound = CURRENT.get();
        return bound == null ? DETACHED : bound;
    }

    /**
     * 객체를 지금 스레드에 바인딩한다. try-with-resources로 닫으면 이전 객체와 문맥(MDC) 값으로 되돌린다. 문맥에도 값을 넣어 두어, 같은 스레드에서
     * 프레임워크(Spring, Tomcat, Lettuce 등)가 찍는 줄에도 trace·user가 붙는다.
     */
    public static Binding bind(AppLogger logger) {
        var binding = new Binding(CURRENT.get(), sink.putContext(logger.contextValues()));
        CURRENT.set(logger);
        return binding;
    }

    /** 사용자를 더한 새 객체. 로그인처럼 요청 도중에 사용자를 알게 된 뒤의 줄에 쓴다. */
    public AppLogger withUser(UUID userId) {
        return new AppLogger(
                traceId, spanId, userId == null ? null : userId.toString(), context, outcome, tags);
    }

    /** 이 객체로 찍는 모든 줄에 붙일 필드를 더한 새 객체. */
    public AppLogger with(LogField... fields) {
        var merged = new ArrayList<>(context);
        for (LogField field : fields) if (field != null) merged.add(field);
        return new AppLogger(traceId, spanId, userId, List.copyOf(merged), outcome, tags);
    }

    /** 이 객체로 찍는 모든 줄에 tags 값을 더한 새 객체. 예: 헬스체크 요청의 {@code health}(Logstash가 info 줄을 버린다). */
    public AppLogger withTag(String tag) {
        if (tag == null || tags.contains(tag)) return this;
        var merged = new ArrayList<>(tags);
        merged.add(tag);
        return new AppLogger(traceId, spanId, userId, context, outcome, List.copyOf(merged));
    }

    /**
     * 이 요청의 결과 줄(request completed)에 붙일 필드를 맡긴다. 예외 처리기는 실패를 따로 찍지 않고 실패 이유(우리 오류 code)를 여기에 맡긴다.
     * 그래서 실패 요청 하나의 결과는 한 줄이다. 바인딩된 요청이 없으면 아무것도 하지 않는다.
     */
    public void addToOutcome(LogField... fields) {
        if (outcome == null) return;
        for (LogField field : fields) if (field != null) outcome.add(field);
    }

    /** 맡겨진 결과 필드. 결과 줄을 쓰는 RequestLogFilter가 읽는다. */
    public LogField[] outcomeFields() {
        return outcome == null ? new LogField[0] : outcome.toArray(LogField[]::new);
    }

    /** 에러 본문 trace_id에 싣는 값. 없으면 null. */
    public String traceId() {
        return traceId;
    }

    /** 정상 동작의 진행 상황. 어느 계층에서 무엇을 처리 중인지와 도메인 ID를 남긴다. */
    public void info(String message, LogField... fields) {
        emit(LogLine.Level.INFO, message, null, fields);
    }

    /** 처리했지만 예상 밖인 상황(재시도, 충돌, 검증 실패, 거절). */
    public void warn(String message, LogField... fields) {
        emit(LogLine.Level.WARN, message, null, fields);
    }

    /** 예외를 함께 남기는 warn. 예외 클래스·메시지·스택이 error.* 필드로 들어간다. */
    public void warn(String message, Throwable cause, LogField... fields) {
        emit(LogLine.Level.WARN, message, cause, fields);
    }

    /** 작업 하나의 실패. 예외 없이 분류만 남길 때는 {@link LogField#errorType(String)}을 함께 넘긴다. */
    public void error(String message, LogField... fields) {
        emit(LogLine.Level.ERROR, message, null, fields);
    }

    /** 예외로 작업 하나가 실패했을 때. 예외 클래스·메시지·스택이 error.* 필드로 들어간다. */
    public void error(String message, Throwable cause, LogField... fields) {
        emit(LogLine.Level.ERROR, message, cause, fields);
    }

    private void emit(LogLine.Level level, String message, Throwable cause, LogField[] fields) {
        // 호출한 클래스·메서드: AppLogger 자신의 프레임을 건너뛴 첫 프레임
        StackWalker.StackFrame caller =
                WALKER.walk(
                        frames ->
                                frames.dropWhile(f -> f.getDeclaringClass() == AppLogger.class)
                                        .findFirst()
                                        .orElse(null));
        // logger 이름은 찍은 클래스다. 그래서 logging.level 설정(예: com.loresentry 패키지)이 우리 줄에 그대로 적용된다
        String loggerName = caller == null ? AppLogger.class.getName() : caller.getClassName();
        if (!sink.enabled(loggerName, level)) return;
        List<Map.Entry<String, Object>> pairs = new ArrayList<>();
        if (caller != null) {
            String[] location = LOCATION.get(caller.getDeclaringClass());
            // 람다 본문이면 javac가 붙인 이름 대신 람다를 감싼 메서드 이름을 쓴다
            String function = caller.getMethodName();
            Matcher lambda = LAMBDA_METHOD.matcher(function);
            if (lambda.matches()) function = lambda.group(1);
            pairs.add(Map.entry("log.origin.function", function));
            pairs.add(Map.entry("log.layer", location[0]));
            pairs.add(Map.entry("log.package", location[1]));
        }
        for (LogField field : context) pairs.addAll(field.entries());
        for (LogField field : fields) {
            if (field != null) pairs.addAll(field.entries());
        }
        // 이 줄 동안만 이 객체의 값으로 문맥을 덮고, 찍은 뒤 원래 값으로 되돌린다
        Map<String, String> previous = sink.putContext(contextValues());
        try {
            sink.write(new LogLine(loggerName, level, message, List.copyOf(pairs), tags, cause));
        } finally {
            sink.restoreContext(previous);
        }
    }

    /**
     * 클래스의 log.layer. {@link LogLayer}가 먼저이고, 없으면 클래스 이름 끝 규칙이다. 안쪽 클래스(익명 클래스 포함)에서 정할 수 없으면 바깥
     * 클래스로 올라가 본다. 끝까지 정하지 못하면 other.
     */
    static String layerOf(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            LogLayer declared = c.getAnnotation(LogLayer.class);
            if (declared != null) return declared.value();
            for (Map.Entry<String, String> rule : LAYER_SUFFIXES) {
                if (c.getSimpleName().endsWith(rule.getKey())) return rule.getValue();
            }
        }
        return "other";
    }

    /** 문맥(MDC)에 넣을 trace·span·user 값. 값이 null인 키는 sink가 지운다. */
    private Map<String, String> contextValues() {
        Map<String, String> values = new HashMap<>();
        values.put(TRACE_ID, traceId);
        values.put(SPAN_ID, spanId);
        values.put(USER_ID, userId);
        return values;
    }

    /** {@link #bind(AppLogger)}의 범위. 닫으면 이전 객체와 문맥 값으로 되돌린다. */
    public static final class Binding implements AutoCloseable {
        private final AppLogger previous;
        private final Map<String, String> previousContext;

        private Binding(AppLogger previous, Map<String, String> previousContext) {
            this.previous = previous;
            this.previousContext = Collections.unmodifiableMap(previousContext);
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            sink.restoreContext(previousContext);
        }
    }
}
