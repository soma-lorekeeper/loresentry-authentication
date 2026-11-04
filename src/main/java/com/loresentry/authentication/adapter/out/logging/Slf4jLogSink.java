package com.loresentry.authentication.adapter.out.logging;

import com.loresentry.authentication.logging.LogLine;
import com.loresentry.authentication.logging.LogSink;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.MarkerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * {@link LogSink}의 SLF4J 구현. AppLogger가 만든 한 줄을 SLF4J 2 fluent API로 넘기고, Logback이 {@link
 * EcsLogFormatter}로 ECS JSON 한 줄을 쓴다(LOG_SCHEMA.md 5절).
 *
 * <p>AppLogger는 이 클래스를 import하지 않는다. 앱이 시작할 때 config(LoggingConfiguration)가 이 객체를 bean으로 만들며 {@code
 * AppLogger.install(...)}로 꽂는다.
 *
 * <ul>
 *   <li>logger 이름은 그 줄을 찍은 클래스다(LogLine.loggerName). 그래서 logging.level 설정이 우리 줄에 그대로 적용된다.
 *   <li>위치와 필드(LogLine.keyValues)는 key-value로, tags는 marker로, 예외는 cause로 넘긴다.
 *   <li>문맥(trace·span·user와 흐름의 경로·메서드·스케줄 이름·이벤트 값)은 MDC에 넣어 같은 스레드의 프레임워크 줄에도 붙게 한다.
 * </ul>
 */
public final class Slf4jLogSink implements LogSink {

    public Slf4jLogSink() {}

    @Override
    public boolean enabled(String loggerName, LogLine.Level level) {
        return LoggerFactory.getLogger(loggerName).isEnabledForLevel(slf4j(level));
    }

    @Override
    public void write(LogLine line) {
        LoggingEventBuilder event =
                LoggerFactory.getLogger(line.loggerName())
                        .atLevel(slf4j(line.level()))
                        .setMessage(line.message());
        for (Map.Entry<String, Object> pair : line.keyValues()) {
            event = event.addKeyValue(pair.getKey(), pair.getValue());
        }
        for (String tag : line.tags()) event = event.addMarker(MarkerFactory.getMarker(tag));
        if (line.cause() != null) event = event.setCause(line.cause());
        event.log();
    }

    @Override
    public Map<String, String> putContext(Map<String, String> values) {
        Map<String, String> previous = new HashMap<>();
        values.forEach(
                (key, value) -> {
                    previous.put(key, MDC.get(key));
                    if (value == null) MDC.remove(key);
                    else MDC.put(key, value);
                });
        return previous;
    }

    @Override
    public void restoreContext(Map<String, String> previous) {
        previous.forEach(
                (key, value) -> {
                    if (value == null) MDC.remove(key);
                    else MDC.put(key, value);
                });
    }

    private static Level slf4j(LogLine.Level level) {
        return switch (level) {
            case INFO -> Level.INFO;
            case WARN -> Level.WARN;
            case ERROR -> Level.ERROR;
        };
    }
}
