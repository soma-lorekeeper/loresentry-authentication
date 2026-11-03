package com.loresentry.authentication.logging;

import java.util.List;
import java.util.Map;

/**
 * {@link AppLogger}가 만든 로그 한 줄. {@link LogSink}가 받아 실제로 출력한다.
 *
 * <p>위치({@code log.origin.function}·{@code log.layer}·{@code log.package})와 필드는 모두 {@code
 * keyValues}에 점 이름으로 들어 있다. 출력 쪽은 이 값을 그대로 옮기기만 하고 고르거나 바꾸지 않는다.
 *
 * @param loggerName 그 줄을 찍은 클래스 이름. ECS {@code log.logger}가 되고, 로그 레벨 설정도 이 이름으로 본다
 * @param level 레벨. 우리 코드는 info·warn·error만 쓴다
 * @param message 짧은 영어 소문자 문장
 * @param keyValues 점 이름(예: {@code loresentry.project_id})과 값의 쌍
 * @param tags 이 줄의 {@code tags}(예: 헬스체크의 {@code health})
 * @param cause 함께 남기는 예외. 없으면 null
 */
public record LogLine(
        String loggerName,
        Level level,
        String message,
        List<Map.Entry<String, Object>> keyValues,
        List<String> tags,
        Throwable cause) {

    /** 우리 코드가 쓰는 세 레벨(LOG_SCHEMA.md 5절). */
    public enum Level {
        INFO,
        WARN,
        ERROR
    }
}
