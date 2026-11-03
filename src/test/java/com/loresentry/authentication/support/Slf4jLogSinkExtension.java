package com.loresentry.authentication.support;

import com.loresentry.authentication.adapter.out.logging.Slf4jLogSink;
import com.loresentry.authentication.logging.AppLogger;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Spring 없이 클래스를 직접 만들어 로그를 확인하는 테스트에 AppLogger의 출력(SLF4J sink)을 꽂는다.
 *
 * <p>앱에서는 config(LoggingConfiguration)가 시작할 때 꽂는다. Spring context를 띄우지 않는 테스트에서는 그 일이 일어나지 않아
 * AppLogger가 아무것도 출력하지 않는다. 그래서 그런 테스트는 {@code @ExtendWith(Slf4jLogSinkExtension.class)}를 붙인다. 앞서 돈
 * 다른 테스트가 이미 꽂아 두었는지에 기대지 않게 하려는 것이다.
 */
public final class Slf4jLogSinkExtension implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        AppLogger.install(new Slf4jLogSink());
    }
}
