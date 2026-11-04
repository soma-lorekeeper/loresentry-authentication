package com.loresentry.authentication.config;

import com.loresentry.authentication.adapter.in.web.RequestLogFilter;
import com.loresentry.authentication.adapter.out.logging.AppLoggerAccessor;
import com.loresentry.authentication.adapter.out.logging.Slf4jLogSink;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogSink;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 로그·trace 배선. LOG_SCHEMA.md 5·7절.
 *
 * <ul>
 *   <li>AppLogger의 출력: adapter의 Slf4jLogSink를 bean으로 만들며 AppLogger에 꽂는다(core는 adapter를 모른다).
 *   <li>요청 시작점·접근 로그 필터(HIGHEST_PRECEDENCE + 2). 서버 span은 EDOT agent의 Tomcat 계측이 필터보다 먼저 열고,
 *       ErrorBoundaryFilter는 +3이라 이 필터 안쪽에서 돈다.
 *   <li>AppLogger를 context-propagation에 등록한다. 시간 제한용 가상 스레드 실행기(Google, Redis)가 AppLogger와 span을 함께
 *       옮긴다.
 *   <li>헬스체크도 다른 요청처럼 span과 로그를 남긴다. 정상 헬스체크의 info 줄은 Logstash가 tags: health로 버린다.
 *   <li>Boot 기본 오류 응답(/error)에도 trace_id를 싣는다.
 * </ul>
 *
 * <p>스케줄 작업 1회의 trace는 EDOT agent의 Spring Scheduling 계측이 연다.
 */
@Configuration(proxyBeanMethods = false)
public class LoggingConfiguration {

    public LoggingConfiguration() {
        AppLoggerAccessor.register();
    }

    /**
     * AppLogger가 줄을 출력할 구현. 이 bean을 만들 때 AppLogger에 꽂는다. DB의 {@code AccountStore}처럼 core는
     * interface(LogSink)만 알고, 구현(SLF4J)은 여기서 이어 준다. AppLogger는 bean이 아니라(요청마다 new) 생성자로 받을 수 없어서
     * static으로 꽂는다. 꽂기 전에 찍힌 줄은 나가지 않는다. 지금 생성자·초기화 블록에서 로그를 찍는 클래스는 없다.
     */
    @Bean
    public LogSink appLogSink() {
        LogSink sink = new Slf4jLogSink();
        AppLogger.install(sink);
        return sink;
    }

    @Bean
    public FilterRegistrationBean<RequestLogFilter> requestLogFilter(
            @Qualifier("requestMappingHandlerMapping")
                    ObjectProvider<RequestMappingHandlerMapping> mappings) {
        // 컨트롤러 매핑은 MVC 설정 bean이다(다른 RequestMappingHandlerMapping과 헷갈리지 않게 이름으로 고른다).
        // 필터를 만들 때가 아니라 요청마다 꺼내므로 bean 생성 순서에 기대지 않는다
        var registration = new FilterRegistrationBean<>(new RequestLogFilter(mappings));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    /**
     * Boot 기본 오류 본문({timestamp, status, error, path})에 trace_id를 더한다. 값은 RequestLogFilter가 요청 속성에
     * 남긴다.
     */
    @Bean
    public DefaultErrorAttributes errorAttributes() {
        return new DefaultErrorAttributes() {
            @Override
            public Map<String, Object> getErrorAttributes(
                    WebRequest request, ErrorAttributeOptions options) {
                Map<String, Object> attributes = super.getErrorAttributes(request, options);
                Object traceId =
                        request.getAttribute(
                                RequestLogFilter.TRACE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                if (traceId != null) attributes.put("trace_id", traceId);
                return attributes;
            }
        };
    }

    /**
     * 아무것도 하지 않는 producer listener. Spring Boot 가 KafkaTemplate 에 기본으로 다는 {@code
     * LoggingProducerListener} 는 보내기에 실패할 때마다 key, payload 앞부분(100자), 예외 메시지를 ERROR 로 찍는다. 이 bean 이
     * 있으면 Boot 는 그것을 만들지 않는다 ({@code @ConditionalOnMissingBean}). 실패는 outbox
     * relay(OutboxPublisher)가 이벤트 id·예외 종류로 이미 남긴다. content 와 같다.
     */
    @Bean
    ProducerListener<Object, Object> silentProducerListener() {
        return new ProducerListener<>() {};
    }
}
