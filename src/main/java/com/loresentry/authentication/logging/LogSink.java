package com.loresentry.authentication.logging;

import java.util.Map;

/**
 * {@link AppLogger}가 만든 줄을 실제로 출력하는 쪽. 구현은 adapter에 있다(SLF4J를 쓰는 {@code Slf4jLogSink}).
 *
 * <p>logging 패키지는 core(service)가 쓰므로 순수 Java여야 한다(ArchitectureTest). 그래서 출력 라이브러리는 이 interface 뒤에
 * 둔다. DB를 {@code AccountStore} 같은 port 뒤에 두는 것과 같다: import는 adapter → logging 방향뿐이고, 실행할 때
 * AppLogger가 adapter의 구현을 부른다. 구현은 앱이 시작할 때 config가 만들어 {@link AppLogger#install(LogSink)}로 꽂는다.
 * Spring 없이 클래스를 만드는 단위 테스트는 테스트 확장(Slf4jLogSinkExtension)이 같은 구현을 꽂는다.
 */
public interface LogSink {
    /** 아직 아무 구현도 꽂히지 않았을 때 쓰는 sink. 아무것도 출력하지 않는다. */
    LogSink NONE =
            new LogSink() {
                @Override
                public boolean enabled(String loggerName, LogLine.Level level) {
                    return false;
                }

                @Override
                public void write(LogLine line) {}

                @Override
                public Map<String, String> putContext(Map<String, String> values) {
                    return Map.of();
                }

                @Override
                public void restoreContext(Map<String, String> previous) {}
            };

    /** 이 클래스·레벨의 줄을 출력하는지(로그 레벨 설정). false면 AppLogger가 줄을 만들지 않는다. */
    boolean enabled(String loggerName, LogLine.Level level);

    /** 한 줄을 출력한다. */
    void write(LogLine line);

    /**
     * 같은 스레드에서 프레임워크가 찍는 줄에도 trace·span·user가 붙도록 값을 넣는다(SLF4J MDC). 값이 null인 키는 지운다.
     *
     * @return 덮기 전의 값. {@link #restoreContext(Map)}에 그대로 넘긴다
     */
    Map<String, String> putContext(Map<String, String> values);

    /** {@link #putContext(Map)}가 돌려준 값으로 되돌린다. */
    void restoreContext(Map<String, String> previous);
}
