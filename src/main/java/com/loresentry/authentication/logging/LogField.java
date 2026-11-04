package com.loresentry.authentication.logging;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 로그 한 줄에 붙일 수 있는 필드. 이름은 LOG_SCHEMA.md 5절의 ECS 필드와 {@code loresentry.*} 필드만 쓴다. gateway·content의
 * 같은 이름 클래스와 같은 규칙이다.
 *
 * <p>생성자를 막고 정적 팩토리만 열어 두었다. 그래서 서비스 코드가 임의의 이름이나 값(이메일, 표시 이름, Google subject, 세션·동의 ID, OAuth
 * code·state)을 필드로 넣을 수 없다. 새 필드가 필요하면 LOG_SCHEMA.md 표에 먼저 추가한 뒤 여기에 팩토리를 만든다.
 *
 * <p>입력값({@link #param})·결과({@link #result})는 LOG_SCHEMA.md 5절 "입력값과 결과" 규칙대로 값을 바꾼다.
 *
 * <ul>
 *   <li>숫자·불리언·enum·UUID: 그대로
 *   <li>문자열: 늘 {@code [redacted]}와 {@code <이름>_length}. 내용이 남지 않는다
 *   <li>허용 목록이 있는 코드 문자열({@link #code}): 목록 안이면 그대로, 밖이면 {@code other}
 *   <li>컬렉션·Map: {@code <이름>_count}
 *   <li>그 밖의 객체(DTO 등): 받는 메서드가 없어 컴파일되지 않는다. 필요한 숫자·코드만 골라 넘긴다
 * </ul>
 *
 * 이름은 파라미터 이름을 snake_case로 쓰고, 같은 이름은 늘 같은 타입으로 쓴다(ES는 처음 들어온 값으로 타입을 정한다).
 *
 * <p>logging 패키지는 core(service)가 쓰므로 순수 Java다(JSON 본문을 받는 오버로드가 없는 것도 그래서다).
 */
public final class LogField {
    /** 가린 문자열 자리에 남기는 값. Logstash mask.rb의 표시와 같다. */
    static final String REDACTED = "[redacted]";

    /** 허용 목록 밖의 코드 값을 대신하는 값. */
    static final String OTHER = "other";

    /** 안전한 스택 트레이스에 남기는 원인 수·원인마다의 프레임 수 상한. */
    private static final int MAX_CAUSES = 8;

    private static final int MAX_FRAMES = 30;
    private static final String PARAM = "loresentry.param.";
    private static final String RESULT = "loresentry.result.";

    private final List<Map.Entry<String, Object>> entries;

    private LogField(List<Map.Entry<String, Object>> entries) {
        this.entries = entries;
    }

    /** 이 필드가 줄에 더하는 이름·값 쌍(점 이름). */
    List<Map.Entry<String, Object>> entries() {
        return entries;
    }

    private static LogField of(String name, Object value) {
        return value == null ? null : new LogField(List.of(Map.entry(name, value)));
    }

    private static LogField uuid(String name, UUID value) {
        return of(name, value == null ? null : value.toString());
    }

    // ---------------------------------------------------------------- Kafka·CloudEvents
    // (loresentry.*)
    // ECS 8.11에 메시징 필드 묶음이 없어 loresentry 아래에 둔다(LOG_SCHEMA.md 5절 필드 표).

    public static LogField kafkaTopic(String topic) {
        return of("loresentry.kafka.topic", topic);
    }

    public static LogField kafkaPartition(int partition) {
        return of("loresentry.kafka.partition", (long) partition);
    }

    public static LogField kafkaOffset(long offset) {
        return of("loresentry.kafka.offset", offset);
    }

    /** 이벤트 ID(ce_id). outbox 행의 ID와 같다. 받는 서비스는 이 값으로 중복을 거른다. */
    public static LogField cloudeventId(UUID id) {
        return uuid("loresentry.cloudevent.id", id);
    }

    /** 이벤트 종류(ce_type). 우리가 보내는 이벤트의 이름(UserDeleted 등)이라 그대로 남긴다. */
    public static LogField cloudeventType(String type) {
        return of("loresentry.cloudevent.type", type);
    }

    /** 이벤트 대상(ce_subject). 그 이벤트가 상태를 담은 대상의 ID다(UserDeleted면 탈퇴한 사용자). 받는 서비스의 inbox 줄과 같은 필드다. */
    public static LogField cloudeventSubject(UUID subject) {
        return uuid("loresentry.cloudevent.subject", subject);
    }

    // ---------------------------------------------------------------- 스케줄 작업 (loresentry.*)

    /**
     * 스케줄 작업 이름(outbox-relay, outbox-purge). 작업 1회의 시작점이 붙여 그 회차의 모든 줄에 남는다. HTTP 요청의 http.route
     * 처럼, 작업 하나가 남긴 줄을 이 값으로 모아 본다. 코드에 정해 둔 이름만 넘긴다.
     */
    public static LogField scheduleName(String name) {
        return of("loresentry.schedule.name", name);
    }

    // ---------------------------------------------------------------- HTTP (ECS·OpenTelemetry)

    /** 요청 메서드(GET, POST 등). 요청 시작점이 url.path·http.route와 함께 요청 하나의 모든 줄에 붙인다. */
    public static LogField httpMethod(String method) {
        return of("http.request.method", method);
    }

    /**
     * 이 서비스가 받은 요청의 실제 경로(쿼리 제외). gateway가 부른 경로다. RequestLogFilter가 요청 하나의 모든 줄에 붙인다. 쿼리에는 OAuth
     * 값이 들어갈 수 있어 남기지 않는다.
     */
    public static LogField urlPath(String path) {
        return of("url.path", path);
    }

    /** Spring이 매칭한 경로 템플릿. OpenTelemetry http.route와 같은 뜻이다. 맞는 경로가 없는 요청(404)에는 없다. */
    public static LogField route(String pattern) {
        return of("http.route", pattern);
    }

    /** 나가는 호출(Google)의 대상 호스트. 들어온 요청이 아니라 나가는 호출의 결과 줄에만 쓴다. gateway의 같은 필드와 같다. */
    public static LogField urlDomain(String host) {
        return of("url.domain", host);
    }

    /**
     * 나가는 호출의 대상 주소(scheme://host:port/path). ECS·OpenTelemetry HTTP client의 {@code url.full}이다. 같은
     * 줄의 {@code url.path}는 이 서비스가 받은 요청의 경로라서, 나가는 호출의 경로는 이 필드로 본다. 쿼리는 넣지 않는다(OAuth 값이 들어갈 수 있다).
     */
    public static LogField urlFull(URI uri) {
        if (uri == null) return null;
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return of("url.full", uri.getScheme() + "://" + uri.getHost() + port + uri.getRawPath());
    }

    public static LogField statusCode(int status) {
        return of("http.response.status_code", (long) status);
    }

    // ---------------------------------------------------------------- 시간·에러 (ECS)

    /**
     * 걸린 시간. ECS event.duration은 나노초다. 시간은 로깅 팩토리(adapter.out.logging의 LogFlow)만 잰다. 로그 패키지가 둘이라
     * public이지만, 서비스 코드가 시간을 재서 넘기지 않도록 LoggingArchitectureTest가 두 로그 패키지 밖에서 부르지 못하게 막는다.
     */
    public static LogField duration(Duration duration) {
        return of("event.duration", duration == null ? null : duration.toNanos());
    }

    /** 예외 없이 실패 종류만 남길 때. 예외 클래스 이름이나 우리 오류 코드(INTERNAL_ERROR 등). */
    public static LogField errorType(String type) {
        return of("error.type", type);
    }

    /** 상대가 돌려준 오류 코드(예: Google 토큰 응답의 error). 정해진 값만 넘긴다. */
    public static LogField errorCode(String code) {
        return of("error.code", code);
    }

    /**
     * 상대가 돌려준 오류 코드 중 아무 문자열이나 올 수 있는 값(브라우저가 보낸 OAuth 콜백 error, Google 응답의 error). 허용 목록(표준
     * OAuth·OIDC 오류 코드)은 도메인 코드 목록이라 부르는 쪽이 넘긴다. 목록 안이면 그대로, 밖이면 other로 남긴다.
     */
    public static LogField errorCode(String code, Set<String> allowed) {
        return of("error.code", code == null ? null : (allowed.contains(code) ? code : OTHER));
    }

    /**
     * 예외 메시지를 뺀 스택 트레이스({@code error.stack_trace}). 원인마다 클래스 이름과 프레임만 적는다. 예외 메시지에는 토큰·OAuth 값·SQL이
     * 섞일 수 있어서, 처리하지 못한 오류(500)의 원인을 남길 때 예외 대신 이 필드를 넘긴다.
     */
    public static LogField safeStackTrace(Throwable failure) {
        if (failure == null) return null;
        StringBuilder trace = new StringBuilder();
        Set<Throwable> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure;
                cause != null && visited.size() < MAX_CAUSES && visited.add(cause);
                cause = cause.getCause()) {
            trace.append(cause.getClass().getName()).append('\n');
            Arrays.stream(cause.getStackTrace())
                    .limit(MAX_FRAMES)
                    .forEach(frame -> trace.append("  at ").append(frame).append('\n'));
        }
        return of("error.stack_trace", trace.toString());
    }

    // ---------------------------------------------------------------- 입력값 (loresentry.param.*)

    public static LogField param(String name, Number value) {
        return of(PARAM + name, value);
    }

    public static LogField param(String name, Boolean value) {
        return of(PARAM + name, value);
    }

    public static LogField param(String name, Enum<?> value) {
        return of(PARAM + name, value == null ? null : value.name());
    }

    /** 참조만 하는 ID는 역할을 붙인 이름으로: param("terms_version_id", id). */
    public static LogField param(String name, UUID value) {
        return uuid(PARAM + name, value);
    }

    /** 문자열은 내용을 남기지 않는다: [redacted]와 길이. 비밀 값(세션·동의 ID, OAuth code·state)은 길이도 넘기지 않는다. */
    public static LogField param(String name, String value) {
        return masked(PARAM, name, value);
    }

    public static LogField param(String name, Collection<?> value) {
        return of(PARAM + name + "_count", value == null ? null : (long) value.size());
    }

    public static LogField param(String name, Map<?, ?> value) {
        return of(PARAM + name + "_count", value == null ? null : (long) value.size());
    }

    /** 정해진 낱말 중 하나인 코드 문자열(locale, provider 등). 허용 목록 안이면 그대로, 밖이면 other로 남긴다. */
    public static LogField code(String name, String value, Set<String> allowed) {
        return of(PARAM + name, value == null ? null : (allowed.contains(value) ? value : OTHER));
    }

    // ---------------------------------------------------------------- 처리 결과 (loresentry.result.*)

    public static LogField result(String name, Number value) {
        return of(RESULT + name, value);
    }

    public static LogField result(String name, Boolean value) {
        return of(RESULT + name, value);
    }

    public static LogField result(String name, Enum<?> value) {
        return of(RESULT + name, value == null ? null : value.name());
    }

    public static LogField result(String name, UUID value) {
        return uuid(RESULT + name, value);
    }

    public static LogField result(String name, String value) {
        return masked(RESULT, name, value);
    }

    public static LogField result(String name, Collection<?> value) {
        return of(RESULT + name + "_count", value == null ? null : (long) value.size());
    }

    public static LogField result(String name, Map<?, ?> value) {
        return of(RESULT + name + "_count", value == null ? null : (long) value.size());
    }

    /** 결과 코드 문자열(상태 이름 등). 허용 목록 밖이면 other. */
    public static LogField resultCode(String name, String value, Set<String> allowed) {
        return of(RESULT + name, value == null ? null : (allowed.contains(value) ? value : OTHER));
    }

    private static LogField masked(String prefix, String name, String value) {
        if (value == null) return null;
        return new LogField(
                List.of(
                        Map.entry(prefix + name, REDACTED),
                        Map.entry(prefix + name + "_length", (long) value.length())));
    }
}
